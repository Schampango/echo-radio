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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spielt Streams und Podcasts im Hintergrund ab, auch wenn der Bildschirm ausgeht.
 * - Bricht die Verbindung ab, wird automatisch neu verbunden.
 * - Bei Radiostreams wird der laufende Titel angezeigt (falls der Sender ihn mitschickt).
 * - Bei Podcasts wird die Position gemerkt und fortgesetzt, solange keine neuere Folge erschienen ist.
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
    /** So lange darf der Stream puffern, bevor neu verbunden wird. */
    private static final int STALL_MS = 20000;
    /** Wartezeiten zwischen den Neuversuchen in Sekunden; danach immer der letzte Wert. */
    private static final int[] RETRY_DELAYS_S = {3, 5, 10, 20, 30};
    private static final int MAX_RETRIES = 20;

    /** Laufende Instanz, nur fuer progress(). Zugriff nur im Haupt-Thread. */
    private static PlayerService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private WifiManager.WifiLock wifiLock;
    private SharedPreferences positions;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private IcyReader icy;

    /** Wird bei jedem Verbindungsversuch und jedem Stopp erhoeht; alte Rueckmeldungen werden ignoriert. */
    private int token;

    // Was gerade gespielt werden soll (null = nichts)
    private String playingId;
    private String playingName;
    private String playingMode;
    private String playingValue;
    /** Aufgeloeste Adresse des aktuellen Versuchs. */
    private String playingUrl;
    private boolean prepared;
    /** Ob in dieser Sitzung schon einmal etwas zu hoeren war. */
    private boolean everPlayed;
    private int retryCount;
    private boolean waitingForRetry;
    private Runnable retryTask;
    private Runnable stallTask;

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
        // Kommt das Netz zurueck, nicht erst die Wartezeit abwarten
        connectivity = getSystemService(ConnectivityManager.class);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                main.post(PlayerService.this::onNetworkAvailable);
            }
        };
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
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
        cancelTimers();
        stopIcy();
        token++;
        prepared = false;
        releasePlayer();
        releaseWifiLock();
        if (networkCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // war nicht registriert
            }
        }
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

    /** Startet die Wiedergabe eines Knopfs (Stream, Podcast oder Sendersuche). */
    static void play(android.content.Context context, Tile t) {
        Intent i = new Intent(context, PlayerService.class);
        i.setAction(ACTION_PLAY);
        i.putExtra(EXTRA_ID, t.id);
        i.putExtra(EXTRA_NAME, t.displayName());
        i.putExtra(EXTRA_MODE, t.type);
        i.putExtra(EXTRA_VALUE, t.value);
        context.startForegroundService(i);
    }

    /** Stoppt die Wiedergabe, falls etwas laeuft. */
    static void stop(android.content.Context context) {
        if (currentId == null) {
            return;
        }
        Intent i = new Intent(context, PlayerService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
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

    // ---------------------------------------------------------------- Ablauf

    private void play(String id, String name, String mode, String value) {
        savePosition();
        cancelTimers();
        stopIcy();
        prepared = false;
        releasePlayer();

        playingId = id;
        playingName = name;
        playingMode = mode;
        playingValue = value;
        playingUrl = null;
        everPlayed = false;
        retryCount = 0;

        startForeground(NOTIFICATION_ID, buildNotification(name, "Verbinde …"));
        if (wifiLock != null) {
            wifiLock.acquire();
        }
        setState(id, name + " – verbinde …");
        attempt();
    }

    /** Ein Verbindungsversuch: Adresse aufloesen, dann abspielen. */
    private void attempt() {
        final int t = ++token;
        waitingForRetry = false;
        final String mode = playingMode;
        final String value = playingValue;
        worker.execute(() -> {
            try {
                String url = resolve(mode, value);
                main.post(() -> {
                    if (t == token) {
                        startPlayer(url, t);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (t != token) {
                        return;
                    }
                    if (isPermanent(e)) {
                        stopAll("Fehler bei " + playingName + ": " + describe(e));
                    } else {
                        connectionLost(describe(e), t);
                    }
                });
            }
        });
    }

    private void startPlayer(String url, int t) {
        playingUrl = url;
        final String id = playingId;
        final String name = playingName;
        final boolean podcast = Tile.RSS.equals(playingMode);
        final int resumeAt = podcast ? savedPosition(id, url) : 0;

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(podcast ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

        mp.setOnPreparedListener(p -> {
            if (t != token) {
                return;
            }
            prepared = true;
            boolean reconnected = retryCount > 0;
            everPlayed = true;
            retryCount = 0;
            int duration = p.getDuration();
            boolean resume = resumeAt > 0 && (duration <= 0 || resumeAt < duration - END_MARGIN_MS);
            if (resume) {
                p.seekTo(resumeAt);
            }
            p.start();
            String text;
            if (resume) {
                text = (reconnected ? "wieder verbunden, " : "fortgesetzt bei ") + formatTime(resumeAt);
            } else {
                text = reconnected ? "wieder verbunden" : "läuft";
            }
            showText(text);
            if (podcast) {
                main.postDelayed(autosave, AUTOSAVE_MS);
            } else {
                startIcy(url, t);
            }
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (t != token) {
                return true;
            }
            boolean unplayable = extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED
                    || extra == MediaPlayer.MEDIA_ERROR_MALFORMED;
            if (unplayable && !everPlayed) {
                stopAll(name + ": Format wird nicht unterstützt");
            } else {
                connectionLost("Verbindung unterbrochen", t);
            }
            return true;
        });
        mp.setOnCompletionListener(p -> {
            if (t != token) {
                return;
            }
            if (podcast) {
                // Folge zu Ende gehoert: naechstes Mal von vorne
                clearPosition(id);
                prepared = false;
                stopAll(name + " beendet");
            } else {
                // Ein Radiostream endet nie von selbst
                connectionLost("Stream abgebrochen", t);
            }
        });
        mp.setOnInfoListener((p, what, extra) -> {
            if (t != token) {
                return false;
            }
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                removeStallCheck();
                stallTask = () -> {
                    if (t == token) {
                        connectionLost("Stream hängt", t);
                    }
                };
                main.postDelayed(stallTask, STALL_MS);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                removeStallCheck();
            }
            return false;
        });
        try {
            mp.setDataSource(url);
            mp.prepareAsync();
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException e) {
            connectionLost(describe(e), t);
        }
    }

    /** Verbindung verloren: aufraeumen und nach einer Pause neu versuchen. */
    private void connectionLost(String reason, int t) {
        if (t != token || playingId == null) {
            return;
        }
        savePosition();
        main.removeCallbacks(autosave);
        removeStallCheck();
        stopIcy();
        prepared = false;
        releasePlayer();
        token++; // Rueckmeldungen dieses Versuchs ignorieren

        retryCount++;
        if (retryCount > MAX_RETRIES) {
            stopAll(playingName + ": " + reason + " – aufgegeben");
            return;
        }
        int delay = RETRY_DELAYS_S[Math.min(retryCount - 1, RETRY_DELAYS_S.length - 1)];
        waitingForRetry = true;
        showText(reason + " – neuer Versuch in " + delay + " s");
        final int expected = token;
        retryTask = () -> {
            if (expected == token && waitingForRetry) {
                attempt();
            }
        };
        main.postDelayed(retryTask, delay * 1000L);
    }

    private void onNetworkAvailable() {
        if (waitingForRetry && retryTask != null) {
            main.removeCallbacks(retryTask);
            main.postDelayed(retryTask, 1500);
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
        int pos = Math.max(0, positionMs);
        try {
            player.seekTo(pos);
        } catch (IllegalStateException e) {
            return;
        }
        if (Tile.RSS.equals(playingMode) && playingUrl != null) {
            positions.edit().putString(playingId, pos + "|" + playingUrl).apply();
        }
    }

    @SuppressWarnings("deprecation")
    private void stopAll(String message) {
        savePosition();
        cancelTimers();
        stopIcy();
        token++;
        waitingForRetry = false;
        prepared = false;
        releasePlayer();
        releaseWifiLock();
        playingId = null;
        playingName = null;
        playingMode = null;
        playingValue = null;
        playingUrl = null;
        setState(null, message == null ? "" : message);
        stopForeground(true);
        stopSelf();
    }

    private void cancelTimers() {
        main.removeCallbacks(autosave);
        removeStallCheck();
        if (retryTask != null) {
            main.removeCallbacks(retryTask);
            retryTask = null;
        }
    }

    private void removeStallCheck() {
        if (stallTask != null) {
            main.removeCallbacks(stallTask);
            stallTask = null;
        }
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

    // ---------------------------------------------------------------- Titel (ICY)

    private void startIcy(String url, int t) {
        stopIcy();
        icy = new IcyReader(url, title -> main.post(() -> {
            if (t == token && playingId != null) {
                showText(title);
            }
        }));
        icy.start();
    }

    private void stopIcy() {
        if (icy != null) {
            icy.stop();
            icy = null;
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

    /** Zeigt "Name – Text" in der Statuszeile und in der Benachrichtigung. */
    private void showText(String text) {
        if (playingId == null) {
            return;
        }
        setState(playingId, playingName + " – " + text);
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(playingName, text));
    }

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
    private static String resolve(String mode, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            throw new Http.NotFoundException("keine Adresse eingetragen");
        }
        if (Tile.RSS.equals(mode)) {
            String xml = Http.getString(value);
            Matcher m = Pattern.compile("<enclosure[^>]*?url=\"([^\"]+)\"").matcher(xml);
            if (!m.find()) {
                throw new Http.NotFoundException("keine Folge im Feed gefunden");
            }
            return m.group(1).replace("&amp;", "&");
        }
        if (Tile.SEARCH.equals(mode) && !value.startsWith("http")) {
            return RadioBrowser.findStreamUrl(value);
        }
        return value;
    }

    /** Fehler, bei denen ein Neuversuch nichts bringt. */
    private static boolean isPermanent(Exception e) {
        if (e instanceof Http.NotFoundException) {
            return true;
        }
        if (e instanceof Http.StatusException) {
            int code = ((Http.StatusException) e).code;
            return code >= 400 && code < 500 && code != 408 && code != 429;
        }
        return false;
    }

    private static String describe(Exception e) {
        if (e instanceof UnknownHostException) {
            return "kein Netz";
        }
        if (e instanceof SocketTimeoutException) {
            return "Zeitüberschreitung";
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
