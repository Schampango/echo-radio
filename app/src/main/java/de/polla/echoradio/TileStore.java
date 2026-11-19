package de.polla.echoradio;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Speichert die Knoepfe als JSON in den App-Einstellungen; Sichern/Wiederherstellen als Datei.
 * Jede Aenderung erhoeht eine Revisionsnummer, damit Geraet und Weboberflaeche
 * sich nicht gegenseitig ueberschreiben.
 */
final class TileStore {

    /** Wird gesendet, wenn die Knoepfe von aussen (Weboberflaeche) geaendert wurden. */
    static final String ACTION_TILES_CHANGED = "de.polla.echoradio.TILES_CHANGED";

    private static final String PREFS = "tiles";
    private static final String KEY = "tiles_json";
    private static final String KEY_REVISION = "revision";
    private static final String KEY_VOICE_MIGRATED = "voice_migrated";
    /** Einstellungen von Version 1.0 (nur geaenderte Adressen). */
    private static final String OLD_PREFS = "streams";

    private TileStore() {
    }

    static synchronized List<Tile> load(Context context) {
        String json = prefs(context).getString(KEY, null);
        if (json != null) {
            try {
                List<Tile> tiles = parseArray(new JSONArray(json));
                // Einmalig: Standardknoepfe aus aelteren Versionen bekommen ihre Sprach-Namen
                if (!prefs(context).getBoolean(KEY_VOICE_MIGRATED, false)) {
                    for (Tile t : tiles) {
                        if (t.voice.isEmpty()) {
                            t.voice = defaultVoice(t.id);
                        }
                    }
                    prefs(context).edit().putBoolean(KEY_VOICE_MIGRATED, true)
                            .putString(KEY, toArray(tiles).toString()).apply();
                }
                return tiles;
            } catch (JSONException e) {
                // kaputte Einstellungen: mit den Standardknoepfen weitermachen
            }
        }
        List<Tile> tiles = defaults();
        SharedPreferences old = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE);
        for (Tile t : tiles) {
            String value = old.getString(t.id, null);
            if (value != null && !value.isEmpty()) {
                t.value = value;
            }
        }
        return tiles;
    }

    static synchronized void save(Context context, List<Tile> tiles) {
        SharedPreferences p = prefs(context);
        p.edit()
                .putString(KEY, toArray(tiles).toString())
                .putInt(KEY_REVISION, p.getInt(KEY_REVISION, 0) + 1)
                .commit();
    }

    static synchronized int revision(Context context) {
        return prefs(context).getInt(KEY_REVISION, 0);
    }

    /** Teilt der Oberflaeche auf dem Geraet mit, dass sie neu laden soll. */
    static void notifyChanged(Context context) {
        Intent i = new Intent(ACTION_TILES_CHANGED);
        i.setPackage(context.getPackageName());
        context.sendBroadcast(i);
    }

    /** Inhalt der Sicherungsdatei. */
    static String exportJson(List<Tile> tiles) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("app", "echo-radio");
        root.put("format", 1);
        root.put("tiles", toArray(tiles));
        return root.toString(2);
    }

    /** Liest eine Sicherungsdatei (oder eine reine Liste von Knoepfen). */
    static List<Tile> parseImport(String text) throws JSONException {
        String s = text.trim();
        JSONArray arr = s.startsWith("[") ? new JSONArray(s) : new JSONObject(s).getJSONArray("tiles");
        return parseArray(arr);
    }

    static JSONArray toArray(List<Tile> tiles) {
        JSONArray arr = new JSONArray();
        for (Tile t : tiles) {
            try {
                arr.put(t.toJson());
            } catch (JSONException ignored) {
                // einzelnen Knopf ueberspringen
            }
        }
        return arr;
    }

    private static List<Tile> parseArray(JSONArray arr) throws JSONException {
        List<Tile> tiles = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            Tile t = Tile.fromJson(arr.getJSONObject(i));
            if (!ids.add(t.id)) {
                t.id = UUID.randomUUID().toString();
                ids.add(t.id);
            }
            tiles.add(t);
        }
        return tiles;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Tile> defaults() {
        int blue = Tile.DEFAULT_COLOR;
        List<Tile> tiles = new ArrayList<>();
        tiles.add(new Tile("news", "News", Tile.RSS,
                "https://www.deutschlandfunk.de/nachrichten-108.xml", blue));
        tiles.add(new Tile("1live", "1 Live", Tile.STREAM,
                "https://wdr-1live-live.icecastssl.wdr.de/wdr/1live/live/mp3/128/stream.mp3", blue));
        tiles.add(new Tile("dlf", "DLF", Tile.STREAM,
                "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3", blue));
        tiles.add(new Tile("brillux", "Brillux", Tile.SEARCH,
                "Brillux Radio", blue));
        tiles.add(new Tile("apple", "Apple\nMusik", Tile.APP,
                "com.apple.android.music", blue));
        tiles.add(new Tile("youtube", "Youtube", Tile.APP,
                "org.schabi.newpipe|com.google.android.youtube|https://m.youtube.com", blue));
        tiles.add(new Tile("bundesliga", "Bundesliga\nKonferenz", Tile.STREAM,
                "https://wdr-wdr2-rheinland.icecastssl.wdr.de/wdr/wdr2/rheinland/mp3/128/stream.mp3", blue));
        for (Tile t : tiles) {
            t.voice = defaultVoice(t.id);
        }
        return tiles;
    }

    /** Weitere Sprach-Namen der Standardknoepfe. */
    static String defaultVoice(String id) {
        switch (id) {
            case "news":
                return "Nachrichten";
            case "1live":
                return "eins live";
            case "dlf":
                return "Deutschlandfunk, d l f";
            case "bundesliga":
                return "Fußball";
            default:
                return "";
        }
    }
}
