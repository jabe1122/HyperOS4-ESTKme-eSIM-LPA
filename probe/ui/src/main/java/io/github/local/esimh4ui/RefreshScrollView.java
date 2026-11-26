package io.github.local.esimh4ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.widget.ScrollView;

/**
 * Page scroller with pull-to-refresh. Pulling down while the page is at the top moves the
 * content down and shows a ring under the header; releasing past the threshold calls the
 * listener, which must end the refresh with {@link #setRefreshing(boolean) setRefreshing(false)}.
 * Without a listener it behaves like a plain {@link ScrollView}.
 */
public final class RefreshScrollView extends ScrollView {
    public interface OnRefreshListener {
        void onRefresh();
    }

    private static final float DRAG_RATE = 0.5f;

    private final int touchSlop;
    private final float triggerPx;
    private final float maxPx;
    private final float ringRadius;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private OnRefreshListener listener;
    private boolean refreshing;
    private boolean pulling;
    private boolean armed;
    private float downX;
    private float downY;
    private float lastY;
    /** Current pull distance in px: how far the content is moved down. */
    private float pull;
    private float spin;
    private ValueAnimator pullAnim;
    private ValueAnimator spinAnim;

    public RefreshScrollView(Context ctx) {
        super(ctx);
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        triggerPx = UiKit.dpf(ctx, 64f);
        maxPx = UiKit.dpf(ctx, 128f);
        ringRadius = UiKit.dpf(ctx, 10f);
        float stroke = UiKit.dpf(ctx, 2.5f);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        setIndicatorColor(0xFF3482FF);
    }

    public void setIndicatorColor(int color) {
        arcPaint.setColor(color);
        trackPaint.setColor(UiKit.withAlpha(color, 0x33));
        invalidate();
    }

    public void setOnRefreshListener(OnRefreshListener l) {
        this.listener = l;
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    /** Ends (or starts, without calling the listener) the refresh animation. */
    public void setRefreshing(boolean value) {
        if (refreshing == value) return;
        refreshing = value;
        if (value) {
            startSpin();
            animatePullTo(triggerPx);
        } else {
            stopSpin();
            animatePullTo(0f);
        }
    }

    private boolean canPull() {
        return listener != null && !refreshing && isEnabled();
    }

    /** True when a move from the down point is a downward pull that should start refreshing. */
    private boolean shouldStartPull(MotionEvent e) {
        if (getScrollY() > 0) {
            // Scrolled away from the top: measure a later pull from here.
            downX = e.getX();
            downY = e.getY();
            return false;
        }
        float dy = e.getY() - downY;
        float dx = e.getX() - downX;
        return dy > touchSlop && dy > Math.abs(dx) * 1.5f;
    }

    private void startPull(MotionEvent e) {
        pulling = true;
        armed = pull >= triggerPx;
        lastY = e.getY();
        if (pullAnim != null) pullAnim.cancel();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!canPull()) return super.onInterceptTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!pulling && shouldStartPull(e)) {
                    startPull(e);
                    return true;
                }
                break;
            default:
                break;
        }
        return pulling || super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!pulling && !canPull()) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                pulling = false;
                return super.onTouchEvent(e);
            case MotionEvent.ACTION_MOVE:
                if (!pulling) {
                    if (!shouldStartPull(e)) return super.onTouchEvent(e);
                    // Take the gesture over from the scroller (no edge stretch at the top).
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    startPull(e);
                }
                setPull(pull + (e.getY() - lastY) * DRAG_RATE);
                lastY = e.getY();
                if (pull >= triggerPx && !armed) {
                    armed = true;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (pull < triggerPx) {
                    armed = false;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!pulling) return super.onTouchEvent(e);
                pulling = false;
                if (e.getActionMasked() == MotionEvent.ACTION_UP && pull >= triggerPx && listener != null) {
                    setRefreshing(true);
                    listener.onRefresh();
                } else {
                    animatePullTo(0f);
                }
                return true;
            default:
                return pulling || super.onTouchEvent(e);
        }
    }

    private void setPull(float value) {
        pull = Math.max(0f, Math.min(maxPx, value));
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        if (child != null) child.setTranslationY(pull);
        invalidate();
    }

    private void animatePullTo(float target) {
        if (pullAnim != null) pullAnim.cancel();
        if (pull == target) return;
        pullAnim = ValueAnimator.ofFloat(pull, target);
        pullAnim.setDuration(240L);
        pullAnim.setInterpolator(UiKit.STANDARD_EASING);
        pullAnim.addUpdateListener(a -> setPull((float) a.getAnimatedValue()));
        pullAnim.start();
    }

    private void startSpin() {
        if (spinAnim != null && spinAnim.isRunning()) return;
        spinAnim = ValueAnimator.ofFloat(0f, 360f);
        spinAnim.setDuration(900L);
        spinAnim.setRepeatCount(ValueAnimator.INFINITE);
        spinAnim.setInterpolator(new LinearInterpolator());
        spinAnim.addUpdateListener(a -> {
            spin = (float) a.getAnimatedValue();
            invalidate();
        });
        spinAnim.start();
    }

    private void stopSpin() {
        if (spinAnim != null) {
            spinAnim.cancel();
            spinAnim = null;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (pull <= 0f) return;
        // The ring sits in the gap that the moved content leaves under the header.
        float progress = Math.min(1f, pull / triggerPx);
        float cx = getWidth() / 2f;
        float cy = getScrollY() + getPaddingTop() + pull / 2f;
        float r = ringRadius * (0.6f + 0.4f * progress);
        int alpha = Math.round(255 * progress);
        arcRect.set(cx - r, cy - r, cx + r, cy + r);
        trackPaint.setAlpha(Math.round(0x33 * progress));
        arcPaint.setAlpha(alpha);
        canvas.drawOval(arcRect, trackPaint);
        if (refreshing) {
            canvas.drawArc(arcRect, spin - 90f, 100f, false, arcPaint);
        } else {
            canvas.drawArc(arcRect, -90f + pull * 0.6f, 330f * progress, false, arcPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (pullAnim != null) pullAnim.cancel();
        stopSpin();
        super.onDetachedFromWindow();
    }
}
