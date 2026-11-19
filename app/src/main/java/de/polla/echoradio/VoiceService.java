package de.polla.echoradio;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lokale Sprachsteuerung mit Vosk: hoert dauerhaft zu und reagiert auf "Alexa, ...".
 * Das deutsche Sprachmodell (ca. 45 MB) wird beim ersten Start heruntergeladen.
 */
public class VoiceService extends Service implements RecognitionListener {

    static final String MODEL_NAME = "vosk-model-small-de-0.15";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";

    private static final String TAG = "EchoRadio";
    private static final String PREFS = "voice";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL = "voice";
    private static final int NOTIFICATION_ID = 3;
    private static final float SAMPLE_RATE = 16000.0f;
    /** So lange wird nach "Alexa" auf den eigentlichen Befehl gewartet. */
    private static final int AWAIT_MS = 7000;
    private static final String LISTENING = "hört zu – sag „Alexa, …“";

    /** Broadcast an die App: Rand anzeigen (Extra "mode", "overlay"). */
    static final String ACTION_LISTENING = "de.polla.echoradio.LISTENING";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_OVERLAY = "overlay";

    /** Zustand fuer Weboberflaeche und App. */
    static volatile String state = "aus";
    /** Zuletzt gehoerter Befehl, fuer die Weboberflaeche. */
    static volatile String lastHeard = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Model model;
    private Recognizer recognizer;
    private SpeechService speech;
    private ToneGenerator tone;
    private volatile boolean destroyed;
    /** "Alexa" wurde gehoert, der Befehl steht noch aus. */
    private boolean awaiting;

    private final Runnable endAwait = () -> finishCommand(ListeningFrame.IDLE);
    /** Rand ueber allen Apps (nur mit Berechtigung "Ueber anderen Apps einblenden"). */
    private ListeningFrame overlay;

    // ---------------------------------------------------------------- Ein/Aus

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply();
        if (on) {
            startIfAllowed(context);
        } else {
            context.stopService(new Intent(context, VoiceService.class));
            state = "aus";
        }
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    static void startIfAllowed(Context context) {
        if (!isEnabled(context)) {
            state = "aus";
            return;
        }
        if (!hasPermission(context)) {
            state = "keine Mikrofon-Berechtigung";
            return;
        }
        try {
            context.startForegroundService(new Intent(context, VoiceService.class));
        } catch (RuntimeException e) {
            state = "konnte nicht starten: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- Lebenszyklus

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        VoiceWords.load(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Sprachsteuerung", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle("Echo Radio – Sprachsteuerung")
                .setContentText("Sag „Alexa, spiel DLF“, „Alexa, Stopp“, „Alexa, lauter“ …")
                .setContentIntent(PendingIntent.getActivity(this, 3,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        try {
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60);
        } catch (RuntimeException e) {
            tone = null;
        }
        addOverlay();
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        worker.execute(this::prepare);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacks(endAwait);
        if (awaiting) {
            PlayerService.duck(false);
        }
        if (speech != null) {
            speech.stop();
            speech.shutdown();
            speech = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        worker.shutdownNow();
        if (tone != null) {
            tone.release();
        }
        if (overlay != null) {
            try {
                getSystemService(WindowManager.class).removeView(overlay.view());
            } catch (RuntimeException ignored) {
                // war nicht angezeigt
            }
            overlay = null;
        }
        showFrame(ListeningFrame.IDLE);
        state = "aus";
        super.onDestroy();
    }

    /** Laeuft im Hintergrund-Thread: Modell besorgen und laden, dann zuhoeren. */
    private void prepare() {
        try {
            File dir = ensureModel();
            state = "lädt Sprachmodell …";
            Model m = new Model(dir.getAbsolutePath());
            Recognizer r = new Recognizer(m, SAMPLE_RATE);
            SpeechService s = new SpeechService(r, SAMPLE_RATE);
            main.post(() -> {
                if (destroyed) {
                    s.shutdown();
                    r.close();
                    m.close();
                    return;
                }
                model = m;
                recognizer = r;
                speech = s;
                speech.startListening(this);
                state = LISTENING;
                Log.i(TAG, "Sprachsteuerung bereit");
            });
        } catch (Exception e) {
            Log.w(TAG, "Sprachsteuerung nicht gestartet", e);
            state = "Fehler: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- Sprachmodell

    private File ensureModel() throws IOException {
        File base = new File(getFilesDir(), "vosk");
        File dir = new File(base, MODEL_NAME);
        if (new File(dir, "am").isDirectory() && new File(dir, "conf").isDirectory()) {
            return dir;
        }
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("Ordner für das Sprachmodell fehlt");
        }
        File zip = new File(base, MODEL_NAME + ".zip.part");
        download(zip);
        state = "entpackt Sprachmodell …";
        unzip(zip, base, dir);
        if (!zip.delete()) {
            Log.w(TAG, "Zip nicht gelöscht: " + zip);
        }
        return dir;
    }

    private void download(File target) throws IOException {
        Log.i(TAG, "Lade Sprachmodell: " + MODEL_URL);
        HttpURLConnection c = Http.open(MODEL_URL, null, null);
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
             OutputStream out = new FileOutputStream(target)) {
            long total = c.getContentLengthLong();
            long done = 0;
            long lastReport = 0;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (destroyed) {
                    throw new IOException("abgebrochen");
                }
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport > 1024 * 1024) {
                    lastReport = done;
                    state = total > 0
                            ? "lädt Sprachmodell herunter (" + (done * 100 / total) + " %)"
                            : "lädt Sprachmodell herunter (" + (done / (1024 * 1024)) + " MB)";
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static void unzip(File zip, File base, File finalDir) throws IOException {
        File tmp = new File(base, "unpack.tmp");
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("Entpacken nicht möglich");
        }
        String root = tmp.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(tmp, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new IOException("ungültiger Eintrag im Archiv");
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
            }
        }
        File unpacked = new File(tmp, MODEL_NAME);
        if (!unpacked.isDirectory()) {
            throw new IOException("Sprachmodell unvollständig");
        }
        deleteRecursive(finalDir);
        if (!unpacked.renameTo(finalDir)) {
            throw new IOException("Sprachmodell nicht gespeichert");
        }
        deleteRecursive(tmp);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "nicht gelöscht: " + f);
        }
    }

    // ---------------------------------------------------------------- Erkennung

    @Override
    public void onPartialResult(String hypothesis) {
        String text = field(hypothesis, "partial");
        if (!awaiting && !text.isEmpty() && VoiceCommands.hasWakeWord(text)) {
            startAwaiting();
        }
    }

    @Override
    public void onResult(String hypothesis) {
        handle(field(hypothesis, "text"));
    }

    @Override
    public void onFinalResult(String hypothesis) {
        handle(field(hypothesis, "text"));
    }

    @Override
    public void onError(Exception e) {
        Log.w(TAG, "Spracherkennung: Fehler", e);
        state = "Fehler: " + e.getMessage();
        restartListeningLater();
    }

    @Override
    public void onTimeout() {
        restartListeningLater();
    }

    private void restartListeningLater() {
        main.postDelayed(() -> {
            if (!destroyed && speech != null) {
                speech.startListening(this);
                state = LISTENING;
            }
        }, 3000);
    }

    /** "Alexa" erkannt: piepen, Musik leiser, auf den Befehl warten. */
    private void startAwaiting() {
        awaiting = true;
        state = "🎤 hört …";
        showFrame(ListeningFrame.LISTENING);
        PlayerService.duck(true);
        if (tone != null) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        }
        main.removeCallbacks(endAwait);
        main.postDelayed(endAwait, AWAIT_MS);
    }

    private static String describe(VoiceCommands.Command cmd, List<Tile> tiles) {
        switch (cmd.kind) {
            case WAKE_ONLY:
                return "hört zu";
            case STOP:
                return "Stopp";
            case VOLUME_UP:
                return "lauter";
            case VOLUME_DOWN:
                return "leiser";
            case SET_VOLUME:
                return "Lautstärke " + cmd.level;
            case PLAY:
                for (Tile t : tiles) {
                    if (t.id.equals(cmd.target.id)) {
                        return "spielt " + t.label.replace('\n', ' ');
                    }
                }
                return "spielt";
            default:
                return "nicht verstanden";
        }
    }

    private void handle(String text) {
        if (text.isEmpty()) {
            return;
        }
        List<VoiceCommands.Target> targets = new ArrayList<>();
        List<Tile> tiles = TileStore.load(this);
        for (Tile t : tiles) {
            targets.add(new VoiceCommands.Target(t.id, t.spokenNames()));
        }
        VoiceCommands.Command cmd = VoiceCommands.parse(text, targets, awaiting);
        if (cmd.kind != VoiceCommands.Kind.NONE || cmd.wakeHeard) {
            Log.i(TAG, "Gehört: „" + text + "“ -> " + cmd);
        }
        if (cmd.kind != VoiceCommands.Kind.NONE || cmd.wakeHeard || awaiting) {
            lastHeard = "„" + text + "“ → " + describe(cmd, tiles);
        }
        switch (cmd.kind) {
            case WAKE_ONLY:
                if (!awaiting) {
                    startAwaiting();
                } else {
                    main.removeCallbacks(endAwait);
                    main.postDelayed(endAwait, AWAIT_MS);
                }
                return;
            case STOP:
                finishCommand(ListeningFrame.OK);
                PlayerService.stop(this);
                toast("⏹ Stopp");
                return;
            case VOLUME_UP:
            case VOLUME_DOWN:
            case SET_VOLUME:
                finishCommand(ListeningFrame.OK);
                changeVolume(cmd);
                return;
            case PLAY:
                finishCommand(ListeningFrame.OK);
                Tile tile = find(tiles, cmd.target.id);
                if (tile != null) {
                    start(tile);
                }
                return;
            default:
                if (cmd.wakeHeard || (awaiting && !cmd.rest.isEmpty())) {
                    finishCommand(ListeningFrame.FAILED);
                    toast("Nicht verstanden: „" + (cmd.rest.isEmpty() ? text : cmd.rest) + "“");
                }
        }
    }

    private void finishCommand(int result) {
        main.removeCallbacks(endAwait);
        boolean wasAwaiting = awaiting;
        if (awaiting) {
            awaiting = false;
            PlayerService.duck(false);
        }
        if (wasAwaiting || result != ListeningFrame.IDLE) {
            showFrame(result);
        }
        if (!destroyed && speech != null) {
            state = LISTENING;
        }
    }

    /** Rand anzeigen: ueber allen Apps (falls erlaubt) und in der Radio-App. */
    private void showFrame(int mode) {
        if (overlay != null) {
            overlay.show(mode);
        }
        Intent i = new Intent(ACTION_LISTENING);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_OVERLAY, overlay != null);
        sendBroadcast(i);
    }

    private void addOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ListeningFrame frame = new ListeningFrame(this);
        try {
            getSystemService(WindowManager.class).addView(frame.view(), lp);
            overlay = frame;
        } catch (RuntimeException e) {
            Log.w(TAG, "Rand über anderen Apps nicht möglich", e);
        }
    }

    private void start(Tile tile) {
        toast("▶ " + tile.displayName());
        if (!Tile.APP.equals(tile.type)) {
            PlayerService.play(this, tile);
            return;
        }
        PlayerService.stop(this);
        for (String raw : tile.value.split("\\|")) {
            String target = raw.trim();
            Intent i = target.startsWith("http")
                    ? new Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    : getPackageManager().getLaunchIntentForPackage(target);
            if (i == null) {
                continue;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return;
            } catch (RuntimeException e) {
                // naechstes Ziel
            }
        }
        toast(tile.displayName() + " ist nicht installiert");
    }

    private void changeVolume(VoiceCommands.Command cmd) {
        AudioManager am = getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int step = Math.max(1, Math.round(max / 10f));
        int target;
        if (cmd.kind == VoiceCommands.Kind.SET_VOLUME) {
            target = Math.round(cmd.level * max / 10f);
        } else if (cmd.kind == VoiceCommands.Kind.VOLUME_UP) {
            target = cur + step;
        } else {
            target = cur - step;
        }
        target = Math.max(0, Math.min(max, target));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
    }

    private static Tile find(List<Tile> tiles, String id) {
        for (Tile t : tiles) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String field(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (JSONException e) {
            return "";
        }
    }
}
