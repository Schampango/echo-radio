package de.polla.echoradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spielt Streams und Podcasts im Hintergrund ab, auch wenn der Bildschirm ausgeht.
 * Bei Podcasts wird die Position gemerkt und beim naechsten Mal fortgesetzt,
 * solange im Feed keine neuere Folge erschienen ist.
 */
public class PlayerService extends Service {

    static final String ACTION_PLAY = "de.polla.echoradio.PLAY";
    static final String ACTION_STOP = "de.polla.echoradio.STOP";
    static final String ACTION_SEEK = "de.polla.echoradio.SEEK";
    static final String ACTION_STATE = "de.polla.echoradio.STATE";

    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_VALUE = "value";
    static final String EXTRA_POSITION = "position";

    /** Knopf, der gerade spielt (oder null). Wird von der Oberflaeche gelesen. */
    static volatile String currentId;
    /** Text fuer die Statuszeile. */
    static volatile String currentStatus = "";

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final int AUTOSAVE_MS = 5000;
    /** Liegt die gemerkte Stelle so knapp vor dem Ende, beginnt die Folge von vorne. */
    private static final int END_MARGIN_MS = 5000;

    /** Laufende Instanz, nur fuer progress(). Zugriff nur im Haupt-Thread. */
    private static PlayerService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private WifiManager.WifiLock wifiLock;
    private SharedPreferences positions;
    /** Wird bei jedem Start/Stopp erhoeht, damit alte Antworten ignoriert werden. */
    private int generation;

    private String playingId;
    private String playingMode;
    private String playingUrl;
    private boolean prepared;

    private final Runnable autosave = new Runnable() {
        @Override
        public void run() {
            savePosition();
            main.postDelayed(this, AUTOSAVE_MS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();
        instance = this;
        positions = getSharedPreferences("podcast_positions", MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Wiedergabe", NotificationManager.IMPORTANCE_LOW));
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "echoradio");
            wifiLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_PLAY.equals(action)) {
            play(intent.getStringExtra(EXTRA_ID),
                    intent.getStringExtra(EXTRA_NAME),
                    intent.getStringExtra(EXTRA_MODE),
                    intent.getStringExtra(EXTRA_VALUE));
        } else if (ACTION_SEEK.equals(action)) {
            seek(intent.getIntExtra(EXTRA_POSITION, 0));
        } else {
            stopAll(null);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        savePosition();
        main.removeCallbacks(autosave);
        generation++;
        prepared = false;
        releasePlayer();
        releaseWifiLock();
        worker.shutdownNow();
        instance = null;
        super.onDestroy();
    }

    /**
     * Position und Dauer des laufenden Podcasts in Millisekunden,
     * oder null, wenn gerade kein Podcast spielt. Nur im Haupt-Thread aufrufen.
     */
    static long[] progress() {
        PlayerService s = instance;
        if (s == null || s.player == null || !s.prepared || !Tile.RSS.equals(s.playingMode)) {
            return null;
        }
        try {
            return new long[]{s.player.getCurrentPosition(), s.player.getDuration()};
        } catch (IllegalStateException e) {
            return null;
        }
    }

    static String formatTime(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0
                ? String.format(Locale.GERMANY, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.GERMANY, "%d:%02d", m, s);
    }

    // ---------------------------------------------------------------- Wiedergabe

    private void play(String id, String name, String mode, String value) {
        savePosition();
        main.removeCallbacks(autosave);
        prepared = false;
        releasePlayer();

        final int gen = ++generation;
        playingId = id;
        playingMode = mode;
        playingUrl = null;

        startForeground(NOTIFICATION_ID, buildNotification(name, "Verbinde …"));
        if (wifiLock != null) {
            wifiLock.acquire();
        }
        setState(id, name + " – verbinde …");

        worker.execute(() -> {
            try {
                String url = resolve(mode, value);
                main.post(() -> {
                    if (gen == generation) {
                        startPlayer(id, name, url, gen);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (gen == generation) {
                        stopAll("Fehler bei " + name + ": " + e.getMessage());
                    }
                });
            }
        });
    }

    private void startPlayer(String id, String name, String url, int gen) {
        playingUrl = url;
        boolean podcast = Tile.RSS.equals(playingMode);
        int resumeAt = podcast ? savedPosition(id, url) : 0;

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(podcast ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(p -> {
            if (gen != generation) {
                return;
            }
            prepared = true;
            int duration = p.getDuration();
            boolean resume = resumeAt > 0 && (duration <= 0 || resumeAt < duration - END_MARGIN_MS);
            if (resume) {
                p.seekTo(resumeAt);
            }
            p.start();
            String text = resume ? "fortgesetzt bei " + formatTime(resumeAt) : "läuft";
            setState(id, name + " – " + text);
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, buildNotification(name, text));
            if (podcast) {
                main.postDelayed(autosave, AUTOSAVE_MS);
            }
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (gen == generation) {
                stopAll(name + ": Wiedergabe fehlgeschlagen (" + what + "/" + extra + ")");
            }
            return true;
        });
        mp.setOnCompletionListener(p -> {
            if (gen == generation) {
                if (podcast) {
                    // Folge zu Ende gehoert: naechstes Mal von vorne
                    clearPosition(id);
                    prepared = false;
                }
                stopAll(name + " beendet");
            }
        });
        try {
            mp.setDataSource(url);
            mp.prepareAsync();
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException e) {
            stopAll("Fehler bei " + name + ": " + e.getMessage());
        }
    }

    private void seek(int positionMs) {
        if (playingId == null) {
            // Dienst wurde nur fuer das Spulen gestartet, es laeuft aber nichts
            stopSelf();
            return;
        }
        if (player == null || !prepared) {
            return;
        }
        try {
            player.seekTo(Math.max(0, positionMs));
        } catch (IllegalStateException e) {
            return;
        }
        if (Tile.RSS.equals(playingMode) && playingUrl != null) {
            positions.edit().putString(playingId, Math.max(0, positionMs) + "|" + playingUrl).apply();
        }
    }

    @SuppressWarnings("deprecation")
    private void stopAll(String message) {
        savePosition();
        main.removeCallbacks(autosave);
        generation++;
        prepared = false;
        releasePlayer();
        releaseWifiLock();
        playingId = null;
        playingMode = null;
        playingUrl = null;
        setState(null, message == null ? "" : message);
        stopForeground(true);
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
                // egal, wird gleich freigegeben
            }
            player.release();
            player = null;
        }
    }

    private void releaseWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    // ---------------------------------------------------------------- Podcast-Positionen

    /** Merkt sich Folge und Stelle des laufenden Podcasts. */
    private void savePosition() {
        if (player == null || !prepared || playingId == null || playingUrl == null
                || !Tile.RSS.equals(playingMode)) {
            return;
        }
        int pos;
        try {
            pos = player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return;
        }
        positions.edit().putString(playingId, pos + "|" + playingUrl).apply();
    }

    /** Gemerkte Stelle, aber nur wenn es noch dieselbe Folge ist. */
    private int savedPosition(String id, String url) {
        String saved = positions.getString(id, null);
        if (saved == null) {
            return 0;
        }
        int bar = saved.indexOf('|');
        if (bar < 0 || !saved.substring(bar + 1).equals(url)) {
            return 0;
        }
        try {
            return Integer.parseInt(saved.substring(0, bar));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void clearPosition(String id) {
        positions.edit().remove(id).apply();
    }

    // ---------------------------------------------------------------- Status & Benachrichtigung

    private void setState(String id, String text) {
        currentId = id;
        currentStatus = text;
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private Notification buildNotification(String title, String text) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, PlayerService.class).setAction(ACTION_STOP), flags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_stop), "Stopp", stop).build())
                .build();
    }

    // ---------------------------------------------------------------- Adressen aufloesen

    /** Laeuft im Hintergrund-Thread. Liefert die abspielbare Adresse. */
    private String resolve(String mode, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            throw new IOException("keine Adresse eingetragen");
        }
        if (Tile.RSS.equals(mode)) {
            String xml = httpGet(value);
            Matcher m = Pattern.compile("<enclosure[^>]*?url=\"([^\"]+)\"").matcher(xml);
            if (!m.find()) {
                throw new IOException("keine Folge im Feed gefunden");
            }
            return m.group(1).replace("&amp;", "&");
        }
        if (Tile.SEARCH.equals(mode) && !value.startsWith("http")) {
            return searchRadioBrowser(value);
        }
        return value;
    }

    /** Sucht den Sender im freien Verzeichnis radio-browser.info. */
    private String searchRadioBrowser(String name) throws IOException {
        String q = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        String json = httpGet("https://all.api.radio-browser.info/json/stations/byname/" + q
                + "?hidebroken=true&order=clickcount&reverse=true&limit=10");
        try {
            JSONArray arr = new JSONArray(json);
            JSONObject best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (best == null) {
                    best = o;
                }
                if (name.equalsIgnoreCase(o.optString("name").trim())) {
                    best = o;
                    break;
                }
            }
            if (best == null) {
                throw new IOException("Sender nicht gefunden");
            }
            String url = best.optString("url_resolved");
            if (url.isEmpty()) {
                url = best.optString("url");
            }
            if (url.isEmpty()) {
                throw new IOException("Sender ohne Stream-Adresse");
            }
            return url;
        } catch (JSONException e) {
            throw new IOException("Antwort von radio-browser.info unlesbar");
        }
    }

    private static String httpGet(String address) throws IOException {
        String current = address;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "EchoRadio/1.0 (Android)");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("Weiterleitung ohne Ziel");
                    }
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("zu viele Weiterleitungen");
    }
}
