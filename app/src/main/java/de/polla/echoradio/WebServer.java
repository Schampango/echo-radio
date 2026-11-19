package de.polla.echoradio;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kleiner HTTP-Server fuer die Weboberflaeche (nur im Heimnetz gedacht).
 *
 * GET  /               Weboberflaeche
 * GET  /api/tiles      Knoepfe + Revision
 * PUT  /api/tiles      Knoepfe ersetzen ({revision, tiles}); 409 bei Konflikt
 * GET  /api/status     laufender Knopf, Statuszeile, Lautstaerke, Revision
 * POST /api/play?id=   Knopf abspielen
 * POST /api/stop       Wiedergabe stoppen
 * POST /api/volume?level=0..100
 * POST /api/voice?on=1|0  Sprachsteuerung ein/aus
 * GET  /api/apps       installierte Apps
 * GET  /api/logos?q=   Logo-Suche bei radio-browser.info
 * GET  /api/export     Sicherung als Datei
 */
final class WebServer implements Runnable {

    private static final int MAX_BODY = 4 * 1024 * 1024;

    private static final class Request {
        String method;
        String path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final class Response {
        final int status;
        final String type;
        final byte[] body;
        String disposition;

        Response(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }
    }

    private final Context context;
    private final int port;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running;
    private ServerSocket server;

    WebServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
    }

    void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(this, "web-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // egal
        }
        pool.shutdownNow();
    }

    @Override
    public void run() {
        while (running) {
            try {
                Socket socket = server.accept();
                pool.execute(() -> handle(socket));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            Request req = readRequest(in);
            if (req == null) {
                return;
            }
            Response res;
            try {
                res = route(req);
            } catch (Exception e) {
                res = json(500, error(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            write(socket.getOutputStream(), res);
        } catch (IOException ignored) {
            // Verbindung abgebrochen
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            return null;
        }
        Request req = new Request();
        req.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int q = target.indexOf('?');
        req.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                req.query.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            }
        }
        String header;
        while ((header = readLine(in)) != null && !header.isEmpty()) {
            int colon = header.indexOf(':');
            if (colon > 0) {
                req.headers.put(header.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        header.substring(colon + 1).trim());
            }
        }
        String length = req.headers.get("content-length");
        if (length != null) {
            int n;
            try {
                n = Integer.parseInt(length);
            } catch (NumberFormatException e) {
                throw new IOException("Content-Length ungültig");
            }
            if (n < 0 || n > MAX_BODY) {
                throw new IOException("Anfrage zu groß");
            }
            byte[] body = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(body, off, n - off);
                if (r < 0) {
                    throw new IOException("Anfrage unvollständig");
                }
                off += r;
            }
            req.body = body;
        }
        return req;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
            if (buf.size() > 8192) {
                throw new IOException("Zeile zu lang");
            }
        }
        if (b == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(OutputStream out, Response res) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(res.status).append(' ').append(reason(res.status)).append("\r\n");
        head.append("Content-Type: ").append(res.type).append("\r\n");
        head.append("Content-Length: ").append(res.body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n");
        if (res.disposition != null) {
            head.append("Content-Disposition: ").append(res.disposition).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(res.body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            default:
                return "Internal Server Error";
        }
    }

    // ---------------------------------------------------------------- Routen

    private Response route(Request req) throws Exception {
        String m = req.method;
        switch (req.path) {
            case "/":
            case "/index.html":
                return asset("web/index.html", "text/html; charset=utf-8");
            case "/api/tiles":
                if ("GET".equals(m)) {
                    return getTiles();
                }
                if ("PUT".equals(m) || "POST".equals(m)) {
                    return putTiles(req);
                }
                break;
            case "/api/status":
                if ("GET".equals(m)) {
                    return json(200, status());
                }
                break;
            case "/api/play":
                if ("POST".equals(m)) {
                    return play(req.query.get("id"));
                }
                break;
            case "/api/stop":
                if ("POST".equals(m)) {
                    PlayerService.stop(context);
                    return json(200, new JSONObject().put("ok", true));
                }
                break;
            case "/api/volume":
                if ("POST".equals(m)) {
                    setVolume(req.query.get("level"));
                }
                if ("POST".equals(m) || "GET".equals(m)) {
                    return json(200, new JSONObject().put("volume", volumePercent()));
                }
                break;
            case "/api/voice":
                if ("POST".equals(m)) {
                    VoiceService.setEnabled(context, !"0".equals(req.query.get("on")));
                    return json(200, status());
                }
                break;
            case "/api/settings":
                if ("GET".equals(m)) {
                    return json(200, AppSettings.load(context).toJson());
                }
                if ("PUT".equals(m) || "POST".equals(m)) {
                    try {
                        return json(200, AppSettings.save(context, new JSONObject(req.bodyText())).toJson());
                    } catch (JSONException e) {
                        return json(400, error("Ungültige Daten: " + e.getMessage()));
                    }
                }
                break;
            case "/api/apps":
                if ("GET".equals(m)) {
                    return json(200, new JSONObject().put("apps", apps()));
                }
                break;
            case "/api/logos":
                if ("GET".equals(m)) {
                    return logos(req.query.get("q"));
                }
                break;
            case "/api/export":
                if ("GET".equals(m)) {
                    Response r = new Response(200, "application/json; charset=utf-8",
                            TileStore.exportJson(TileStore.load(context)).getBytes(StandardCharsets.UTF_8));
                    r.disposition = "attachment; filename=\"echo-radio-knoepfe.json\"";
                    return r;
                }
                break;
            default:
                return json(404, error("Nicht gefunden"));
        }
        return json(405, error("Methode nicht erlaubt"));
    }

    private Response getTiles() throws JSONException {
        JSONObject root = new JSONObject();
        synchronized (TileStore.class) {
            root.put("revision", TileStore.revision(context));
            root.put("tiles", TileStore.toArray(TileStore.load(context)));
        }
        return json(200, root);
    }

    private Response putTiles(Request req) throws JSONException {
        JSONObject body;
        List<Tile> tiles;
        try {
            body = new JSONObject(req.bodyText());
            tiles = TileStore.parseImport(body.toString());
        } catch (JSONException e) {
            return json(400, error("Ungültige Daten: " + e.getMessage()));
        }
        int base = body.optInt("revision", -1);
        int revision;
        synchronized (TileStore.class) {
            if (base != TileStore.revision(context)) {
                return json(409, error("Auf dem Echo wurde inzwischen etwas geändert. Bitte neu laden.")
                        .put("revision", TileStore.revision(context)));
            }
            TileStore.save(context, tiles);
            revision = TileStore.revision(context);
        }
        TileStore.notifyChanged(context);
        return json(200, new JSONObject().put("revision", revision));
    }

    private JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        String id = PlayerService.currentId;
        o.put("playing", id == null ? JSONObject.NULL : id);
        o.put("status", PlayerService.currentStatus == null ? "" : PlayerService.currentStatus);
        o.put("volume", volumePercent());
        o.put("revision", TileStore.revision(context));
        o.put("voice", new JSONObject()
                .put("enabled", VoiceService.isEnabled(context))
                .put("state", VoiceService.state));
        return o;
    }

    private Response play(String id) throws JSONException {
        if (id == null) {
            return json(400, error("id fehlt"));
        }
        for (Tile t : TileStore.load(context)) {
            if (t.id.equals(id)) {
                if (Tile.APP.equals(t.type)) {
                    return json(409, error("Apps lassen sich nur direkt am Echo starten"));
                }
                PlayerService.play(context, t);
                return json(200, new JSONObject().put("ok", true));
            }
        }
        return json(404, error("Knopf nicht gefunden"));
    }

    private int volumePercent() {
        AudioManager am = context.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0 : Math.round(cur * 100f / max);
    }

    private void setVolume(String level) {
        if (level == null) {
            return;
        }
        int percent;
        try {
            percent = Math.max(0, Math.min(100, Integer.parseInt(level.trim())));
        } catch (NumberFormatException e) {
            return;
        }
        AudioManager am = context.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(percent * max / 100f), AudioManager.FLAG_SHOW_UI);
    }

    private JSONArray apps() throws JSONException {
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        List<String[]> list = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(context.getPackageName())) {
                list.add(new String[]{ri.loadLabel(pm).toString(), pkg});
            }
        }
        list.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        JSONArray arr = new JSONArray();
        for (String[] app : list) {
            arr.put(new JSONObject().put("name", app[0]).put("package", app[1]));
        }
        return arr;
    }

    private Response logos(String query) throws JSONException {
        if (query == null || query.trim().isEmpty()) {
            return json(400, error("Suchbegriff fehlt"));
        }
        List<RadioBrowser.Station> found;
        try {
            found = RadioBrowser.searchFlexible(query, 40);
        } catch (IOException e) {
            return json(500, error("Suche fehlgeschlagen: " + e.getMessage()));
        }
        JSONArray arr = new JSONArray();
        for (RadioBrowser.Station s : found) {
            if (!s.favicon.isEmpty()) {
                arr.put(new JSONObject()
                        .put("name", s.name)
                        .put("country", s.country)
                        .put("favicon", s.favicon)
                        .put("url", s.url));
            }
        }
        return json(200, new JSONObject().put("results", arr).put("found", found.size()));
    }

    // ---------------------------------------------------------------- Hilfen

    private Response asset(String name, String type) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new Response(200, type, out.toByteArray());
        }
    }

    private static Response json(int status, JSONObject o) {
        return new Response(status, "application/json; charset=utf-8",
                o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message);
        } catch (JSONException ignored) {
            // kann bei einem String nicht passieren
        }
        return o;
    }
}
