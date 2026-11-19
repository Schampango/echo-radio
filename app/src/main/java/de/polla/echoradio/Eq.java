package de.polla.echoradio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.util.Log;

/**
 * Equalizer fuer die Wiedergabe. Alle Player nutzen dieselbe Audio-Sitzung,
 * daher bleibt der Equalizer beim Senderwechsel erhalten.
 * Pegel werden in Millibel gespeichert (100 mB = 1 dB).
 */
final class Eq {

    private static final String TAG = "EchoRadio";
    private static final String PREFS = "equalizer";
    private static final String ENABLED = "enabled";
    private static final String LEVELS = "levels";
    private static final String PRESET = "preset";

    private static int session;
    private static Equalizer equalizer;

    private Eq() {
    }

    /** Gemeinsame Audio-Sitzung aller Player. */
    static synchronized int session(Context c) {
        if (session == 0) {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            session = am.generateAudioSessionId();
        }
        return session;
    }

    /** Equalizer an der Sitzung, mit den gespeicherten Werten. Null, wenn das Geraet keinen hat. */
    static synchronized Equalizer get(Context c) {
        if (equalizer == null) {
            try {
                equalizer = new Equalizer(0, session(c));
                applySaved(c);
            } catch (RuntimeException e) {
                Log.w(TAG, "Equalizer nicht verfuegbar", e);
                equalizer = null;
            }
        }
        return equalizer;
    }

    /** Uebernimmt die gespeicherten Werte in den laufenden Equalizer. */
    static synchronized void applySaved(Context c) {
        Equalizer e = equalizer;
        if (e == null) {
            return;
        }
        try {
            short bands = e.getNumberOfBands();
            int[] levels = savedLevels(c, bands);
            for (short b = 0; b < bands; b++) {
                e.setBandLevel(b, (short) levels[b]);
            }
            e.setEnabled(isEnabled(c));
        } catch (RuntimeException ex) {
            Log.w(TAG, "Equalizer: Werte nicht gesetzt", ex);
        }
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(ENABLED, false);
    }

    /** Name der zuletzt gewaehlten Voreinstellung oder leer. */
    static String presetName(Context c) {
        return prefs(c).getString(PRESET, "");
    }

    static int[] savedLevels(Context c, int bands) {
        int[] out = new int[bands];
        String raw = prefs(c).getString(LEVELS, "");
        String[] parts = raw.isEmpty() ? new String[0] : raw.split(",");
        if (parts.length != bands) {
            return out; // anderes Geraet oder noch nie eingestellt: alles 0 dB
        }
        for (int i = 0; i < bands; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    static void save(Context c, boolean enabled, int[] levels, String preset) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(levels[i]);
        }
        prefs(c).edit()
                .putBoolean(ENABLED, enabled)
                .putString(LEVELS, sb.toString())
                .putString(PRESET, preset == null ? "" : preset)
                .apply();
    }

    /** Sofort hoerbar, ohne zu speichern (fuer die Schieberegler). */
    static synchronized void preview(boolean enabled, int[] levels) {
        Equalizer e = equalizer;
        if (e == null) {
            return;
        }
        try {
            for (short b = 0; b < levels.length && b < e.getNumberOfBands(); b++) {
                e.setBandLevel(b, (short) levels[b]);
            }
            e.setEnabled(enabled);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Equalizer: Vorschau fehlgeschlagen", ex);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
