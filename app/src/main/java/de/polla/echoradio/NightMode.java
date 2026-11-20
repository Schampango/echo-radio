package de.polla.echoradio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;
import java.util.Calendar;

/**
 * Nachtschaltung: Display zur eingestellten Zeit ganz aus, morgens wieder an.
 * Ausschalten geht nur als Geraeteadministrator ("Bildschirm sperren"), siehe {@link ScreenAdmin}.
 * Radio und Sprachsteuerung laufen nachts weiter.
 */
public final class NightMode extends BroadcastReceiver {

    private static final String TAG = "EchoRadio";
    static final String ACTION_START = "de.polla.echoradio.NIGHT_START";
    static final String ACTION_END = "de.polla.echoradio.NIGHT_END";
    /** An die App: Nacht hat begonnen oder ist vorbei. */
    static final String ACTION_CHANGED = "de.polla.echoradio.NIGHT_CHANGED";

    /** Darf die App das Display ausschalten? */
    static boolean canTurnOff(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(admin(c));
    }

    static ComponentName admin(Context c) {
        return new ComponentName(c, ScreenAdmin.class);
    }

    static boolean isNight(Context c) {
        return isNight(AppSettings.load(c));
    }

    static boolean isNight(AppSettings s) {
        if (!s.nightEnabled || s.nightStart == s.nightEnd) {
            return false;
        }
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return s.nightStart < s.nightEnd
                ? m >= s.nightStart && m < s.nightEnd
                // ueber Mitternacht, z. B. 23:00 bis 06:00
                : m >= s.nightStart || m < s.nightEnd;
    }

    /** Wecker fuer den naechsten Beginn und das naechste Ende stellen (oder loeschen). */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent start = pending(c, ACTION_START);
        PendingIntent end = pending(c, ACTION_END);
        am.cancel(start);
        am.cancel(end);
        AppSettings s = AppSettings.load(c);
        if (!s.nightEnabled || s.nightStart == s.nightEnd) {
            return;
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(s.nightStart), start);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(s.nightEnd), end);
    }

    private static PendingIntent pending(Context c, String action) {
        Intent i = new Intent(c, NightMode.class).setAction(action);
        return PendingIntent.getBroadcast(c, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Naechster Zeitpunkt (heute oder morgen) fuer Minuten nach Mitternacht. */
    private static long next(int minutes) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 1000) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    /** Display aus. Ohne Berechtigung false (dann dunkelt die App nur ab). */
    static boolean turnOff(Context c) {
        if (!canTurnOff(c)) {
            return false;
        }
        try {
            c.getSystemService(DevicePolicyManager.class).lockNow();
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Display ausschalten nicht erlaubt", e);
            return false;
        }
    }

    /** Display an und Radio-App nach vorne. */
    @SuppressWarnings("deprecation")
    static void turnOn(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "EchoRadio:morning");
        wl.acquire(5000);
        try {
            c.startActivity(new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException e) {
            Log.w(TAG, "Radio-App nicht gestartet", e);
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            Log.i(TAG, "Nachtschaltung: Display aus");
            if (!turnOff(c)) {
                Log.w(TAG, "Nachtschaltung: keine Berechtigung, Display wird nur abgedunkelt");
            }
        } else if (ACTION_END.equals(action)) {
            Log.i(TAG, "Nachtschaltung: Display an");
            turnOn(c);
        }
        c.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(c.getPackageName()));
        // Wecker fuer die naechste Nacht
        schedule(c);
    }
}
