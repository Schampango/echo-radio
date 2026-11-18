package de.polla.echoradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spielt Streams im Hintergrund ab, auch wenn der Bildschirm ausgeht.
 */
public class PlayerService extends Service {

    static final String ACTION_PLAY = "de.polla.echoradio.PLAY";
    static final String ACTION_STOP = "de.polla.echoradio.STOP";
    static final String ACTION_STATE = "de.polla.echoradio.STATE";

    static final String EXTRA_ID = "id";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_VALUE = "value";

    /** Knopf, der gerade spielt (oder null). Wird von der Oberflaeche gelesen. */
    static volatile String currentId;
    /** Text fuer die Statuszeile. */
    static volatile String currentStatus = "";

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private WifiManager.WifiLock wifiLock;
    /** Wird bei jedem Start/Stopp erhoeht, damit alte Antworten ignoriert werden. */
    private int generation;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCreate() {
        super.onCreate();
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
        } else {
            stopAll(null);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        generation++;
        releasePlayer();
        releaseWifiLock();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- Wiedergabe

    private void play(String id, String name, String mode, String value) {
        releasePlayer();
        final int gen = ++generation;
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
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(p -> {
            if (gen != generation) {
                return;
            }
            p.start();
            setState(id, name + " – läuft");
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, buildNotification(name, "Läuft"));
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (gen == generation) {
                stopAll(name + ": Wiedergabe fehlgeschlagen (" + what + "/" + extra + ")");
            }
            return true;
        });
        mp.setOnCompletionListener(p -> {
            if (gen == generation) {
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

    @SuppressWarnings("deprecation")
    private void stopAll(String message) {
        generation++;
        releasePlayer();
        releaseWifiLock();
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
                throw new IOException("keine Sendung im Feed gefunden");
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
