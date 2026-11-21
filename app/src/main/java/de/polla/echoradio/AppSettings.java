package de.polla.echoradio;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import org.json.JSONException;
import org.json.JSONObject;

/** Einstellungen fuer Display, Bildschirmschoner und Nachtschaltung (App und Weboberflaeche). */
final class AppSettings {

    static final String ACTION_CHANGED = "de.polla.echoradio.SETTINGS_CHANGED";

    private static final String PREFS = "settings";
    private static final String DIM_SECONDS = "dim_seconds";
    private static final String DIM_LEVEL = "dim_level";
    private static final String RETURN_MINUTES = "return_minutes";
    private static final String SHOW_CLOCK = "show_clock";
    private static final String NIGHT_ENABLED = "night_enabled";
    private static final String NIGHT_START = "night_start";
    private static final String NIGHT_END = "night_end";
    private static final String BG_COLOR = "bg_color";
    private static final String BG_DIM = "bg_dim";

    /** Abdunkeln nach so vielen Sekunden ohne Beruehrung, 0 = nie. */
    final int dimSeconds;
    /** Helligkeit im abgedunkelten Zustand in Prozent, 0 = schwarz. */
    final int dimLevel;
    /** Aus einer anderen App nach so vielen Minuten zurueck zur Radio-App, 0 = nie. */
    final int returnMinutes;
    /** Im abgedunkelten Zustand Uhr und laufenden Titel zeigen. */
    final boolean showClock;
    /** Display nachts ganz ausschalten. */
    final boolean nightEnabled;
    /** Beginn und Ende der Nacht in Minuten nach Mitternacht (z. B. 120 = 02:00). */
    final int nightStart;
    final int nightEnd;
    /** Hintergrundfarbe des Startbildschirms. */
    final int bgColor;
    /** Hintergrundbild abdunkeln, 0..80 %. */
    final int bgDim;

    private AppSettings(int dimSeconds, int dimLevel, int returnMinutes, boolean showClock,
                        boolean nightEnabled, int nightStart, int nightEnd, int bgColor, int bgDim) {
        this.bgColor = 0xFF000000 | bgColor;
        this.bgDim = clamp(bgDim, 0, 80);
        this.dimSeconds = clamp(dimSeconds, 0, 3600);
        this.dimLevel = clamp(dimLevel, 0, 100);
        this.returnMinutes = clamp(returnMinutes, 0, 240);
        this.showClock = showClock;
        this.nightEnabled = nightEnabled;
        this.nightStart = clamp(nightStart, 0, 1439);
        this.nightEnd = clamp(nightEnd, 0, 1439);
    }

    static AppSettings load(Context c) {
        SharedPreferences p = prefs(c);
        return new AppSettings(p.getInt(DIM_SECONDS, 60), p.getInt(DIM_LEVEL, 5), p.getInt(RETURN_MINUTES, 0),
                p.getBoolean(SHOW_CLOCK, true),
                p.getBoolean(NIGHT_ENABLED, false), p.getInt(NIGHT_START, 120), p.getInt(NIGHT_END, 480),
                p.getInt(BG_COLOR, Color.WHITE), p.getInt(BG_DIM, 20));
    }

    /** Uebernimmt nur die Felder, die im JSON stehen, speichert und sagt der App Bescheid. */
    static AppSettings save(Context c, JSONObject o) {
        AppSettings old = load(c);
        AppSettings s = new AppSettings(
                o.optInt("dimSeconds", old.dimSeconds),
                o.optInt("dimLevel", old.dimLevel),
                o.optInt("returnMinutes", old.returnMinutes),
                o.optBoolean("showClock", old.showClock),
                o.optBoolean("nightEnabled", old.nightEnabled),
                parseTime(o.opt("nightStart"), old.nightStart),
                parseTime(o.opt("nightEnd"), old.nightEnd),
                parseColor(o.optString("bgColor", ""), old.bgColor),
                o.optInt("bgDim", old.bgDim));
        prefs(c).edit()
                .putInt(DIM_SECONDS, s.dimSeconds)
                .putInt(DIM_LEVEL, s.dimLevel)
                .putInt(RETURN_MINUTES, s.returnMinutes)
                .putBoolean(SHOW_CLOCK, s.showClock)
                .putBoolean(NIGHT_ENABLED, s.nightEnabled)
                .putInt(NIGHT_START, s.nightStart)
                .putInt(NIGHT_END, s.nightEnd)
                .putInt(BG_COLOR, s.bgColor)
                .putInt(BG_DIM, s.bgDim)
                .commit();
        NightMode.schedule(c);
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
        return s;
    }

    JSONObject toJson(Context c) throws JSONException {
        return new JSONObject()
                .put("dimSeconds", dimSeconds)
                .put("dimLevel", dimLevel)
                .put("returnMinutes", returnMinutes)
                .put("showClock", showClock)
                .put("nightEnabled", nightEnabled)
                .put("nightStart", formatTime(nightStart))
                .put("nightEnd", formatTime(nightEnd))
                .put("nightPossible", NightMode.canTurnOff(c))
                .put("nightNow", NightMode.isNight(this))
                .put("bgColor", Tile.toHex(bgColor))
                .put("bgDim", bgDim)
                .put("bgImage", Background.has(c))
                .put("bgVersion", Background.version(c))
                .put("bgDarkText", !darkBackground(c));
    }

    /** "02:00" oder Minuten als Zahl. */
    static int parseTime(Object v, int fallback) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            String[] p = ((String) v).trim().split(":");
            try {
                int h = Integer.parseInt(p[0].trim());
                int m = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
                if (h >= 0 && h < 24 && m >= 0 && m < 60) {
                    return h * 60 + m;
                }
            } catch (NumberFormatException ignored) {
                // Fallback
            }
        }
        return fallback;
    }

    static int parseColor(String hex, int fallback) {
        if (hex == null || !hex.trim().matches("#?[0-9a-fA-F]{6}")) {
            return fallback;
        }
        String h = hex.trim();
        return 0xFF000000 | Integer.parseInt(h.startsWith("#") ? h.substring(1) : h, 16);
    }

    /** Text auf dem Startbildschirm hell (true) oder dunkel? */
    boolean darkBackground(Context c) {
        float luma = Background.has(c)
                ? Background.luma(c) * (1f - bgDim / 100f)
                : Background.luma(bgColor);
        return luma < 0.55f;
    }

    static String formatTime(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
