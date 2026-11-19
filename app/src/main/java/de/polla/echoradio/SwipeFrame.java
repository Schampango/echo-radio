package de.polla.echoradio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Rahmen, der waagerechtes Wischen erkennt, auch wenn es auf einem Knopf beginnt. */
@SuppressLint("ViewConstructor")
class SwipeFrame extends FrameLayout {

    interface OnSwipeListener {
        /** +1 = naechste Seite (nach links gewischt), -1 = vorige Seite. */
        void onSwipe(int direction);
    }

    private final int slop;
    private float downX;
    private float downY;
    private boolean swiping;
    private OnSwipeListener listener;

    SwipeFrame(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setOnSwipeListener(OnSwipeListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiping = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (isHorizontalSwipe(ev)) {
                    swiping = true;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swiping = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!swiping && isHorizontalSwipe(ev)) {
                    swiping = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (swiping && listener != null) {
                    listener.onSwipe(ev.getX() < downX ? 1 : -1);
                }
                swiping = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                return true;
            default:
                return true;
        }
    }

    private boolean isHorizontalSwipe(MotionEvent ev) {
        float dx = Math.abs(ev.getX() - downX);
        float dy = Math.abs(ev.getY() - downY);
        return dx > slop * 3 && dx > dy * 1.5f;
    }
}
