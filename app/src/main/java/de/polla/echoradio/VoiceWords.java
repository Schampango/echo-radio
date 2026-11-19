package de.polla.echoradio;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Speichert die Befehlswoerter der Sprachsteuerung (aenderbar in der Weboberflaeche). */
final class VoiceWords {

    private static final String PREFS = "voice_words";
    private static final String KEY = "words";
    static final String[] KEYS = {"wake", "stop", "louder", "quieter", "volume", "fillers"};

    private VoiceWords() {
    }

    /** Laedt die gespeicherten Woerter in die Spracherkennung. */
    static synchronized void load(Context c) {
        String raw = prefs(c).getString(KEY, "");
        if (raw.isEmpty()) {
            VoiceCommands.words = VoiceCommands.Words.defaults();
            return;
        }
        try {
            VoiceCommands.words = fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            VoiceCommands.words = VoiceCommands.Words.defaults();
        }
    }

    static synchronized void save(Context c, JSONObject body) {
        VoiceCommands.Words w = fromJson(body);
        VoiceCommands.words = w;
        try {
            prefs(c).edit().putString(KEY, toJson(w).toString()).apply();
        } catch (JSONException ignored) {
            // kann bei Strings nicht passieren
        }
    }

    static synchronized void reset(Context c) {
        prefs(c).edit().remove(KEY).apply();
        VoiceCommands.words = VoiceCommands.Words.defaults();
    }

    static JSONObject toJson(VoiceCommands.Words w) throws JSONException {
        return new JSONObject()
                .put("wake", new JSONArray(w.wake))
                .put("stop", new JSONArray(w.stop))
                .put("louder", new JSONArray(w.louder))
                .put("quieter", new JSONArray(w.quieter))
                .put("volume", new JSONArray(w.volume))
                .put("fillers", new JSONArray(sorted(w.fillers)));
    }

    private static VoiceCommands.Words fromJson(JSONObject o) {
        VoiceCommands.Words d = VoiceCommands.Words.defaults();
        return new VoiceCommands.Words(
                list(o, "wake", d.wake), list(o, "stop", d.stop), list(o, "louder", d.louder),
                list(o, "quieter", d.quieter), list(o, "volume", d.volume),
                list(o, "fillers", sorted(d.fillers)));
    }

    private static List<String> list(JSONObject o, String key, List<String> fallback) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) {
            return fallback;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> sorted(Collection<String> in) {
        List<String> out = new ArrayList<>(in);
        java.util.Collections.sort(out);
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
