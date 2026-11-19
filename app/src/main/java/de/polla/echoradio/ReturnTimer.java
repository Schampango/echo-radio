package de.polla.echoradio;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Holt die Radio-App nach einer Weile wieder nach vorne, wenn eine andere App
 * gestartet wurde. Der Prozess laeuft dank der Vordergrunddienste weiter.
 * Aus dem Hintergrund darf Android 10+ nur Apps mit "Ueber anderen Apps einblenden" starten.
 */
final class ReturnTimer {

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable pending;

    private ReturnTimer() {
    }

    static void schedule(Context context, int minutes) {
        cancel();
        if (minutes <= 0) {
            return;
        }
        Context app = context.getApplicationContext();
        pending = () -> {
            pending = null;
            try {
                app.startActivity(new Intent(app, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (RuntimeException e) {
                Log.w("EchoRadio", "Zurueck zur App nicht moeglich", e);
            }
        };
        HANDLER.postDelayed(pending, minutes * 60_000L);
    }

    static void cancel() {
        if (pending != null) {
            HANDLER.removeCallbacks(pending);
            pending = null;
        }
    }
}
