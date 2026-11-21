package de.polla.echoradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.metadata.icy.IcyInfo;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spielt Streams und Podcasts im Hintergrund ab (mit ExoPlayer), auch wenn der Bildschirm ausgeht.
 * - Bricht die Verbindung ab oder haengt der Sender, wird automatisch neu verbunden.
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
    /** Name des laufenden Senders (oder null). */
    static volatile String currentStation;
    /** Songtitel vom Sender, meist "Interpret - Titel" (oder leer). */
    static volatile String currentTitle = "";

    private static final String TAG = "EchoRadio";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final int AUTOSAVE_MS = 5000;
    /** Liegt die gemerkte Stelle so knapp vor dem Ende, beginnt die Folge von vorne. */
    private static final int END_MARGIN_MS = 5000;
    /** So lange darf Verbinden oder Puffern dauern, bevor neu verbunden wird. */
    private static final int STALL_MS = 20000;
    /** Wartezeiten zwischen den Neuversuchen in Sekunden; danach immer der letzte Wert. */
    private static final int[] RETRY_DELAYS_S = {3, 5, 10, 20, 30};
    private static final int MAX_RETRIES = 20;
    /** Aus- und Einblenden beim Senderwechsel. */
    private static final int FADE_OUT_MS = 200;
    private static final int FADE_IN_MS = 300;
    private static final int FADE_STEPS = 10;
    /** Lautstaerke der Musik, waehrend ein Sprachbefehl gesprochen wird. */
    private static final float DUCK_VOLUME = 0.15f;

    /** Laufende Instanz, nur fuer progress(). Zugriff nur im Haupt-Thread. */
    private static PlayerService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ExoPlayer player;
    private SharedPreferences positions;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

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
    /** Musik gerade leiser wegen eines Sprachbefehls. */
    private boolean ducked;
    /** Ob in dieser Sitzung schon einmal etwas zu hoeren war. */
    private boolean everPlayed;
    private String lastTitle;
    /** Liest Songtitel ueber eine eigene Verbindung, falls ExoPlayer keine liefert. */
    private IcyReader icy;
    private Runnable icyFallback;
    /** Nach so vielen Sekunden ohne Titel von ExoPlayer selbst nachsehen. */
    private static final int ICY_FALLBACK_MS = 12000;
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
    public void onCreate() {
        super.onCreate();
        instance = this;
        positions = getSharedPreferences("podcast_positions", MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Wiedergabe", NotificationManager.IMPORTANCE_LOW));
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
        token++;
        prepared = false;
        releasePlayer(false);
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
        long duration = s.player.getDuration();
        return new long[]{s.player.getCurrentPosition(), duration == C.TIME_UNSET ? 0 : duration};
    }

    /**
     * Musik leiser stellen, waehrend ein Sprachbefehl gesprochen wird. Nur im Haupt-Thread aufrufen.
     */
    static void duck(boolean on) {
        PlayerService s = instance;
        if (s == null) {
            return;
        }
        s.ducked = on;
        if (s.player != null) {
            s.player.setVolume(on ? DUCK_VOLUME : 1f);
        }
    }

    /** Startet die Wiedergabe eines Knopfs (Stream, Podcast oder Sendersuche). */
    static void play(Context context, Tile t) {
        Intent i = new Intent(context, PlayerService.class);
        i.setAction(ACTION_PLAY);
        i.putExtra(EXTRA_ID, t.id);
        i.putExtra(EXTRA_NAME, t.displayName());
        i.putExtra(EXTRA_MODE, t.type);
        i.putExtra(EXTRA_VALUE, t.value);
        context.startForegroundService(i);
    }

    /** Stoppt die Wiedergabe, falls etwas laeuft. */
    static void stop(Context context) {
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
                Log.i(TAG, "Abspielen: " + value + " -> " + url);
                main.post(() -> {
                    if (t == token) {
                        startPlayer(url, t);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Adresse nicht aufgelöst: " + value, e);
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
        lastTitle = null;
        currentTitle = "";
        final String id = playingId;
        final String name = playingName;
        final boolean podcast = Tile.RSS.equals(playingMode);
        final int resumeAt = podcast ? savedPosition(id, url) : 0;

        ExoPlayer p = new ExoPlayer.Builder(this).build();
        player = p;
        // Feste Sitzung, damit der Equalizer fuer jeden Sender gilt
        p.setAudioSessionId(Eq.session(this));
        Eq.get(this);
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(podcast ? C.AUDIO_CONTENT_TYPE_SPEECH : C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), false);
        // Haelt CPU und WLAN wach, solange gespielt wird
        p.setWakeMode(C.WAKE_MODE_NETWORK);
        // Leise starten, eingeblendet wird beim Start (verhindert Knacken beim Senderwechsel)
        p.setVolume(0f);
        p.addListener(new Player.Listener() {
            private boolean started;

            @Override
            public void onPlaybackStateChanged(int state) {
                if (t != token) {
                    return;
                }
                if (state == Player.STATE_READY) {
                    removeStallCheck();
                    if (!started) {
                        started = true;
                        onStarted(p, resumeAt, podcast, url);
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (started) {
                        scheduleStallCheck("Stream hängt", t);
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (podcast) {
                        // Folge zu Ende gehoert: naechstes Mal von vorne
                        clearPosition(id);
                        prepared = false;
                        stopAll(name + " beendet");
                    } else {
                        // Ein Radiostream endet nie von selbst
                        connectionLost("Stream abgebrochen", t);
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Player-Fehler " + error.getErrorCodeName() + " url=" + url, error);
                if (t != token) {
                    return;
                }
                int code = error.errorCode;
                boolean unplayable = code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                        || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
                if (unplayable && !everPlayed) {
                    stopAll(name + ": Format wird nicht unterstützt");
                } else {
                    connectionLost("Verbindung unterbrochen", t);
                }
            }

            @Override
            public void onMetadata(Metadata metadata) {
                if (t != token || podcast) {
                    return;
                }
                for (int i = 0; i < metadata.length(); i++) {
                    Metadata.Entry entry = metadata.get(i);
                    if (entry instanceof IcyInfo) {
                        showTitle(((IcyInfo) entry).title, t);
                    }
                }
            }

            @Override
            public void onMediaMetadataChanged(MediaMetadata m) {
                if (t != token || podcast || m.title == null) {
                    return;
                }
                String title = m.title.toString();
                if (m.artist != null && !title.contains(m.artist)) {
                    title = m.artist + " – " + title;
                }
                showTitle(title, t);
            }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (isHls(url)) {
            // HLS sicher erkennen, auch wenn die Adresse nach Weiterleitung anders endet
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        try {
            p.setMediaItem(item.build());
            // Bei Podcasts erst an die gemerkte Stelle springen, dann abspielen
            p.setPlayWhenReady(resumeAt <= 0);
            p.prepare();
        } catch (RuntimeException e) {
            // z. B. fehlender Baustein fuer ein Stream-Format: nicht die ganze App abstuerzen lassen
            Log.w(TAG, "Stream kann nicht gestartet werden: " + url, e);
            stopAll(name + ": Format wird nicht unterstützt");
            return;
        }
        scheduleStallCheck("keine Antwort vom Sender", t);
    }

    /** Der Player ist zum ersten Mal bereit. */
    private void onStarted(ExoPlayer p, int resumeAt, boolean podcast, String url) {
        prepared = true;
        fadeIn(p);
        boolean reconnected = retryCount > 0;
        everPlayed = true;
        retryCount = 0;
        boolean resume = false;
        if (resumeAt > 0) {
            long duration = p.getDuration();
            resume = duration == C.TIME_UNSET || duration <= 0 || resumeAt < duration - END_MARGIN_MS;
            if (resume) {
                p.seekTo(resumeAt);
            }
            p.play();
        }
        String text;
        if (resume) {
            text = (reconnected ? "wieder verbunden, " : "fortgesetzt bei ") + formatTime(resumeAt);
        } else if (lastTitle != null) {
            // Der Songtitel kam schon vor dem Start an: nicht mit "läuft" ueberschreiben
            text = lastTitle;
        } else {
            text = reconnected ? "wieder verbunden" : "läuft";
        }
        showText(text);
        if (podcast) {
            main.postDelayed(autosave, AUTOSAVE_MS);
        } else {
            // Liefert ExoPlayer keine Songtitel, selbst beim Sender nachfragen
            final int t = token;
            icyFallback = () -> {
                // HLS hat keine ICY-Titel, dort nicht nachfragen
                if (t == token && lastTitle == null && icy == null && !isHls(url)) {
                    Log.i(TAG, "Keine Titel von ExoPlayer, lese selbst mit");
                    icy = new IcyReader(url, title -> main.post(() -> showTitle(title, t)));
                    icy.start();
                }
            };
            main.postDelayed(icyFallback, ICY_FALLBACK_MS);
        }
        Log.i(TAG, "Spielt: " + url);
    }

    /** Zeigt einen Songtitel an (aus ExoPlayer oder dem eigenen Leser). */
    private void showTitle(String title, int t) {
        if (t != token || playingId == null || title == null) {
            return;
        }
        String clean = title.trim();
        if (clean.isEmpty() || clean.equals(lastTitle)) {
            return;
        }
        lastTitle = clean;
        currentTitle = sameName(clean, playingName) ? "" : clean;
        // Manche Sender schicken zwischen den Songs nur ihren eigenen Namen
        showText(sameName(clean, playingName) ? "läuft" : clean);
    }

    /** "1LIVE" und "1 Live" gelten als gleich. */
    private static boolean sameName(String a, String b) {
        if (b == null) {
            return false;
        }
        String x = a.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9äöüß]", "");
        String y = b.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9äöüß]", "");
        return !x.isEmpty() && x.equals(y);
    }

    private void scheduleStallCheck(String reason, int t) {
        removeStallCheck();
        stallTask = () -> {
            if (t == token) {
                connectionLost(reason, t);
            }
        };
        main.postDelayed(stallTask, STALL_MS);
    }

    /** Verbindung verloren: aufraeumen und nach einer Pause neu versuchen. */
    private void connectionLost(String reason, int t) {
        if (t != token || playingId == null) {
            return;
        }
        Log.w(TAG, playingName + ": " + reason);
        savePosition();
        main.removeCallbacks(autosave);
        removeStallCheck();
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
        player.seekTo(pos);
        if (Tile.RSS.equals(playingMode) && playingUrl != null) {
            positions.edit().putString(playingId, pos + "|" + playingUrl).apply();
        }
    }

    @SuppressWarnings("deprecation")
    private void stopAll(String message) {
        savePosition();
        cancelTimers();
        token++;
        waitingForRetry = false;
        prepared = false;
        releasePlayer();
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
        releasePlayer(true);
    }

    /**
     * Gibt den Player frei. Laeuft gerade etwas, wird erst kurz ausgeblendet:
     * ein hartes Abschalten mitten im Ton erzeugt ein lautes Knacken.
     */
    private void releasePlayer(boolean fade) {
        if (icyFallback != null) {
            main.removeCallbacks(icyFallback);
            icyFallback = null;
        }
        if (icy != null) {
            icy.stop();
            icy = null;
        }
        if (player != null) {
            ExoPlayer old = player;
            player = null;
            if (fade && old.isPlaying()) {
                fadeOutAndRelease(old);
            } else {
                old.release();
            }
        }
    }

    private void fadeOutAndRelease(ExoPlayer old) {
        float start = old.getVolume();
        for (int i = 1; i <= FADE_STEPS; i++) {
            final float volume = start * (1f - i / (float) FADE_STEPS);
            final boolean last = i == FADE_STEPS;
            main.postDelayed(() -> {
                old.setVolume(Math.max(0f, volume));
                if (last) {
                    old.stop();
                    old.release();
                }
            }, (long) i * FADE_OUT_MS / FADE_STEPS);
        }
    }

    /** Blendet den neuen Sender sanft ein. */
    private void fadeIn(ExoPlayer p) {
        for (int i = 1; i <= FADE_STEPS; i++) {
            final float volume = i / (float) FADE_STEPS;
            main.postDelayed(() -> {
                if (player == p) {
                    p.setVolume(ducked ? Math.min(volume, DUCK_VOLUME) : volume);
                }
            }, (long) i * FADE_IN_MS / FADE_STEPS);
        }
    }

    // ---------------------------------------------------------------- Podcast-Positionen

    /** Merkt sich Folge und Stelle des laufenden Podcasts. */
    private void savePosition() {
        if (player == null || !prepared || playingId == null || playingUrl == null
                || !Tile.RSS.equals(playingMode)) {
            return;
        }
        long pos = player.getCurrentPosition();
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
            return (int) Long.parseLong(saved.substring(0, bar));
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
        if (text != null && !text.isEmpty()) {
            Log.i(TAG, text);
        }
        currentId = id;
        currentStatus = text;
        currentStation = id == null ? null : playingName;
        if (id == null) {
            currentTitle = "";
        }
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
        String stream = value;
        if (Tile.SEARCH.equals(mode) && !value.startsWith("http")) {
            stream = RadioBrowser.findStreamUrl(value);
        }
        // Weiterleitungen (z. B. Lastverteiler) selbst aufloesen
        return Http.finalUrl(stream);
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

    /** HLS-Playlist (.m3u8)? */
    static boolean isHls(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        return u.endsWith(".m3u8") || u.contains("/hls/");
    }
}
