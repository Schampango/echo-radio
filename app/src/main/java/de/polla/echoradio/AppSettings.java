package de.polla.echoradio;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/** Einstellungen fuer Display und Bildschirmschoner (App und Weboberflaeche). */
final class AppSettings {

    static final String ACTION_CHANGED = "de.polla.echoradio.SETTINGS_CHANGED";

    private static final String PREFS = "settings";
    private static final String DIM_SECONDS = "dim_seconds";
    private static final String DIM_LEVEL = "dim_level";
    private static final String RETURN_MINUTES = "return_minutes";
    private static final String SHOW_CLOCK = "show_clock";

    /** Abdunkeln nach so vielen Sekunden ohne Beruehrung, 0 = nie. */
    final int dimSeconds;
    /** Helligkeit im abgedunkelten Zustand in Prozent, 0 = schwarz. */
    final int dimLevel;
    /** Aus einer anderen App nach so vielen Minuten zurueck zur Radio-App, 0 = nie. */
    final int returnMinutes;
    /** Im abgedunkelten Zustand Uhr und laufenden Titel zeigen. */
    final boolean showClock;

    private AppSettings(int dimSeconds, int dimLevel, int returnMinutes, boolean showClock) {
        this.showClock = showClock;
        this.dimSeconds = clamp(dimSeconds, 0, 3600);
        this.dimLevel = clamp(dimLevel, 0, 100);
        this.returnMinutes = clamp(returnMinutes, 0, 240);
    }

    static AppSettings load(Context c) {
        SharedPreferences p = prefs(c);
        return new AppSettings(p.getInt(DIM_SECONDS, 60), p.getInt(DIM_LEVEL, 5), p.getInt(RETURN_MINUTES, 0),
                p.getBoolean(SHOW_CLOCK, true));
    }

    /** Speichert und sagt der App Bescheid. */
    static AppSettings save(Context c, int dimSeconds, int dimLevel, int returnMinutes, boolean showClock) {
        AppSettings s = new AppSettings(dimSeconds, dimLevel, returnMinutes, showClock);
        prefs(c).edit()
                .putInt(DIM_SECONDS, s.dimSeconds)
                .putInt(DIM_LEVEL, s.dimLevel)
                .putInt(RETURN_MINUTES, s.returnMinutes)
                .putBoolean(SHOW_CLOCK, s.showClock)
                .apply();
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
        return s;
    }

    /** Uebernimmt nur die Felder, die im JSON stehen. */
    static AppSettings save(Context c, JSONObject o) {
        AppSettings old = load(c);
        return save(c,
                o.optInt("dimSeconds", old.dimSeconds),
                o.optInt("dimLevel", old.dimLevel),
                o.optInt("returnMinutes", old.returnMinutes),
                o.optBoolean("showClock", old.showClock));
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("dimSeconds", dimSeconds)
                .put("dimLevel", dimLevel)
                .put("returnMinutes", returnMinutes)
                .put("showClock", showClock);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
