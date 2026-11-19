package de.polla.echoradio;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Farbiger Rand um das ganze Display als Rueckmeldung der Sprachsteuerung:
 * rot pulsierend = hoert zu, gruen = verstanden, orange = nicht verstanden.
 */
final class ListeningFrame {

    static final int IDLE = 0;
    static final int LISTENING = 1;
    static final int OK = 2;
    static final int FAILED = 3;

    private static final int RED = 0xFFE53935;
    private static final int GREEN = 0xFF43A047;
    private static final int ORANGE = 0xFFFB8C00;
    private static final int FLASH_MS = 800;

    private final View view;
    private final int strokePx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ObjectAnimator pulse;
    private final Runnable hide = this::hideNow;

    ListeningFrame(Context context) {
        view = new View(context);
        view.setVisibility(View.GONE);
        view.setClickable(false);
        view.setFocusable(false);
        strokePx = Math.round(14 * context.getResources().getDisplayMetrics().density);
    }

    View view() {
        return view;
    }

    /** Nur im Haupt-Thread aufrufen. */
    void show(int mode) {
        main.removeCallbacks(hide);
        stopPulse();
        switch (mode) {
            case LISTENING:
                setColor(RED);
                view.setAlpha(1f);
                view.setVisibility(View.VISIBLE);
                pulse = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.45f);
                pulse.setDuration(600);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.start();
                break;
            case OK:
            case FAILED:
                setColor(mode == OK ? GREEN : ORANGE);
                view.setAlpha(1f);
                view.setVisibility(View.VISIBLE);
                main.postDelayed(hide, FLASH_MS);
                break;
            default:
                hideNow();
        }
    }

    private void hideNow() {
        stopPulse();
        view.animate().alpha(0f).setDuration(200).withEndAction(() -> view.setVisibility(View.GONE)).start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        view.animate().cancel();
    }

    private void setColor(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Color.TRANSPARENT);
        d.setStroke(strokePx, color);
        view.setBackground(d);
    }
}
