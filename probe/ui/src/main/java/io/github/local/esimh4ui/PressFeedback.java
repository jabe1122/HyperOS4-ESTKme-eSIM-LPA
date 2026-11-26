package io.github.local.esimh4ui;

import android.animation.TimeInterpolator;
import android.view.MotionEvent;
import android.view.View;

/**
 * Card press feedback like Miuix's sink + tilt feedback in SukiSU: while pressed the view sinks a
 * little and tilts toward the finger, as if a real card were pushed down at that point; on
 * release it springs back with a small bounce.
 *
 * <p>Call {@link #onTouchEvent} from the view's {@code onTouchEvent}. Only views that receive the
 * whole gesture (clickable views) get the feedback.
 */
public final class PressFeedback {
    private static final float PRESSED_SCALE = 0.97f;
    /** Maximum tilt, in degrees, around the horizontal axis (finger near top or bottom edge). */
    private static final float MAX_TILT_X = 5f;
    /** Maximum tilt around the vertical axis; smaller, since cards are wider than tall. */
    private static final float MAX_TILT_Y = 3f;

    /** Damped spring: ends at 1 with about 5% overshoot. */
    private static final TimeInterpolator SPRING = t ->
            (float) (1.0 - Math.exp(-6.0 * t) * Math.cos(2.0 * Math.PI * t));

    private final View view;
    private boolean pressed;

    public PressFeedback(View view) {
        this.view = view;
    }

    public void onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                // A far camera keeps the perspective subtle on wide views.
                view.setCameraDistance(Math.max(view.getWidth(), view.getHeight()) * 8f);
                tiltToward(e.getX(), e.getY(), 140L);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!pressed) break;
                if (e.getX() < 0 || e.getY() < 0 || e.getX() > view.getWidth() || e.getY() > view.getHeight()) {
                    release();
                } else {
                    tiltToward(e.getX(), e.getY(), 80L);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                break;
            default:
                break;
        }
    }

    private void tiltToward(float x, float y, long duration) {
        float w = view.getWidth();
        float h = view.getHeight();
        if (w <= 0 || h <= 0) return;
        float nx = Math.max(-1f, Math.min(1f, (x - w / 2f) / (w / 2f)));
        float ny = Math.max(-1f, Math.min(1f, (y - h / 2f) / (h / 2f)));
        // Push the pressed side away from the viewer: a positive rotationX moves the top edge
        // back, a positive rotationY moves the right edge forward.
        view.animate()
                .scaleX(PRESSED_SCALE)
                .scaleY(PRESSED_SCALE)
                .rotationX(ny * MAX_TILT_X)
                .rotationY(nx * MAX_TILT_Y)
                .setDuration(duration)
                .setInterpolator(UiKit.STANDARD_EASING)
                .start();
    }

    private void release() {
        if (!pressed) return;
        pressed = false;
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .rotationX(0f)
                .rotationY(0f)
                .setDuration(520L)
                .setInterpolator(SPRING)
                .start();
    }
}
