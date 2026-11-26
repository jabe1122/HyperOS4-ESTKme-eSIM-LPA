package io.github.local.esimh4ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal horizontal pager: children are laid out side by side at full size and the user can
 * swipe between them. Vertical gestures stay with the scrolling pages.
 */
public final class PagerHost extends ViewGroup {
    public interface OnPageChangeListener {
        /** Called once the pager settles on a different page through a swipe or {@link #setPage}. */
        void onPageSelected(int index);
    }

    public interface OnPageScrollListener {
        /** Called on every scroll step; {@code position} is fractional, e.g. 0.5 is halfway to page 1. */
        void onPageScrolled(float position);
    }

    private final int touchSlop;
    private final int minFlingVelocity;
    private OnPageChangeListener listener;
    private final List<OnPageChangeListener> observers = new ArrayList<>();
    private OnPageScrollListener scrollListener;
    private int current = 0;
    private int pendingPage = -1;

    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging;
    private VelocityTracker velocity;
    private ValueAnimator settle;

    public PagerHost(Context ctx) {
        super(ctx);
        ViewConfiguration vc = ViewConfiguration.get(ctx);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity() * 4;
    }

    public void setOnPageChangeListener(OnPageChangeListener l) {
        this.listener = l;
    }

    /** Extra listener for internal use (the scaffold), kept apart from the app's listener. */
    public void addOnPageChangeObserver(OnPageChangeListener l) {
        observers.add(l);
    }

    public void setOnPageScrollListener(OnPageScrollListener l) {
        this.scrollListener = l;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        int w = getWidth();
        if (scrollListener != null && w > 0) scrollListener.onPageScrolled(l / (float) w);
    }

    private void notifyPage(int index) {
        if (listener != null) listener.onPageSelected(index);
        for (OnPageChangeListener o : observers) o.onPageSelected(index);
    }

    public int getCurrentPage() {
        return current;
    }

    public void setPage(int index, boolean animate) {
        index = clamp(index);
        if (getWidth() == 0) {
            pendingPage = index;
            if (index != current) {
                current = index;
                notifyPage(index);
            }
            return;
        }
        animateTo(index, animate);
    }

    private int clamp(int index) {
        return Math.max(0, Math.min(getChildCount() - 1, index));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cw, ch);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        }
        if (pendingPage >= 0) {
            current = pendingPage;
            pendingPage = -1;
        }
        if (settle == null || !settle.isRunning()) {
            scrollTo(current * w, 0);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (getChildCount() < 2) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = settle != null && settle.isRunning();
                if (dragging) settle.cancel();
                trackVelocity(e);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    dragging = true;
                    lastX = e.getX();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                trackVelocity(e);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) recycleVelocity();
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (getChildCount() < 2) return false;
        trackVelocity(e);
        int w = getWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging && Math.abs(e.getX() - downX) > touchSlop) dragging = true;
                if (!dragging) return true;
                float delta = lastX - e.getX();
                lastX = e.getX();
                float max = (getChildCount() - 1) * w;
                float next = getScrollX() + delta;
                if (next < 0 || next > max) {
                    next = getScrollX() + delta * 0.35f; // resistance at the edges
                }
                scrollTo(Math.round(next), 0);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int target = current;
                if (velocity != null && w > 0) {
                    velocity.computeCurrentVelocity(1000);
                    float vx = velocity.getXVelocity();
                    if (Math.abs(vx) > minFlingVelocity) {
                        target = vx < 0 ? current + 1 : current - 1;
                    } else {
                        target = Math.round(getScrollX() / (float) w);
                    }
                }
                dragging = false;
                recycleVelocity();
                animateTo(clamp(target), true);
                return true;
            }
            default:
                return true;
        }
    }

    private void animateTo(int index, boolean animate) {
        int w = getWidth();
        int targetX = index * w;
        boolean changed = index != current;
        current = index;
        if (settle != null) settle.cancel();
        if (!animate || getScrollX() == targetX) {
            scrollTo(targetX, 0);
        } else {
            settle = ValueAnimator.ofInt(getScrollX(), targetX);
            settle.setDuration(280L);
            settle.setInterpolator(UiKit.STANDARD_EASING);
            settle.addUpdateListener(a -> scrollTo((int) a.getAnimatedValue(), 0));
            settle.start();
        }
        if (changed) notifyPage(index);
    }

    private void trackVelocity(MotionEvent e) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }
}
