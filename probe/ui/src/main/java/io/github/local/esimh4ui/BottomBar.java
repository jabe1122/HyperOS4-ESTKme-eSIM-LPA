package io.github.local.esimh4ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Bottom navigation bar.
 *
 * <ul>
 *   <li>Material 3: 80dp navigation bar with a pill indicator behind the selected icon.</li>
 *   <li>Miuix: 64dp navigation bar with frosted (optionally progressive) glass, or the SukiSU
 *       floating capsule: 28dp side margins, 64dp tall, a 56dp selection capsule, and liquid
 *       glass when enabled (see {@link GlassLinearLayout}).</li>
 * </ul>
 *
 * Dragging sideways across the bar switches tabs as the finger passes over them. In Miuix the
 * selection (the capsule of the floating bar, and the icon colors) slides with the finger and
 * with page swipes, see {@link #setIndicatorPosition}.
 */
public final class BottomBar extends GlassLinearLayout {
    public interface OnSelectListener {
        void onSelect(int index);
    }

    private final boolean miuix;
    private final boolean floating;
    private final LinearLayout[] items;
    private final ImageView[] icons;
    private final TextView[] labels;
    private final FrameLayout[] indicators;
    private OnSelectListener listener;
    private int selected = -1;

    private final int touchSlop;
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF indicatorRect = new RectF();
    /** Fractional index of the selection, e.g. 0.5 is halfway between the first two tabs. */
    private float indicatorPos = -1f;
    private ValueAnimator indicatorAnim;
    /** 0..1: how much the capsule is lifted while dragging. */
    private float pressProgress = 0f;
    private ValueAnimator pressAnim;
    private boolean dragging;
    private float downX;
    private float downY;

    public BottomBar(Context ctx, UiKit.Palette p, String[] titles, int[] iconRes) {
        super(ctx, p);
        this.miuix = UiKit.isMiuix();
        this.floating = miuix && ThemeConfig.barFloating;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setClickable(true); // swallow touches so they do not reach the page underneath
        touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        indicatorRimPaint.setStyle(Paint.Style.STROKE);
        indicatorRimPaint.setStrokeWidth(UiKit.dpf(ctx, 1f));

        int n = titles.length;
        items = new LinearLayout[n];
        icons = new ImageView[n];
        labels = new TextView[n];
        indicators = new FrameLayout[n];
        for (int i = 0; i < n; i++) {
            buildItem(ctx, i, titles[i], iconRes[i]);
        }

        int container = palette.cardBg | 0xFF000000; // Miuix surfaceContainer
        if (floating) {
            setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), UiKit.dp(ctx, 4), UiKit.dp(ctx, 4));
            setElevation(UiKit.dpf(ctx, 6f));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                    outline.setAlpha(p.isNight ? 0.2f : 0.1f);
                }
            });
            setClipToOutline(true);
            if (ThemeConfig.liquidGlass) {
                setGlass(true, UiKit.withAlpha(container, p.isNight ? 0x66 : 0x4D), container);
                setLiquid(true, true);
            } else {
                onGlassFailed(container);
            }
        } else if (miuix && ThemeConfig.barGlass) {
            int alpha = p.hasBackground ? Math.round(ThemeConfig.cardAlpha * 2.55f) : 0xDE; // 0.87 like SukiSU
            setGlass(true, UiKit.withAlpha(p.pageBg, alpha), p.pageBg | 0xFF000000);
            if (ThemeConfig.progressiveBlur) setFade(Fade.BOTTOM);
        } else {
            onGlassFailed(barColor() | 0xFF000000);
        }
    }

    @Override
    protected void onGlassFailed(int fallback) {
        setBackground(floating
                ? UiKit.roundedRect(getContext(), fallback, 999f, 0, 0)
                : new ColorDrawable(fallback));
    }

    private int barColor() {
        if (miuix) return floating ? palette.cardBg : palette.pageBg;
        return palette.tileBg;
    }

    /** Height of the bar's content, without the navigation bar inset. */
    public int contentHeightPx() {
        Context ctx = getContext();
        if (floating) return UiKit.dp(ctx, 64);
        return UiKit.dp(ctx, miuix ? 64 : 80);
    }

    public boolean isFloating() {
        return floating;
    }

    private void buildItem(Context ctx, int index, String title, int iconRes) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(VERTICAL);
        item.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = floating
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(0, contentHeightPx(), 1f);
        item.setLayoutParams(lp);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(title);
        item.setOnClickListener(v -> {
            if (index != selected) {
                select(index);
                if (listener != null) listener.onSelect(index);
            }
        });

        FrameLayout indicator = new FrameLayout(ctx);
        int iconPx = UiKit.dp(ctx, miuix ? 26 : 24);
        LinearLayout.LayoutParams ilp = miuix
                ? new LinearLayout.LayoutParams(iconPx, iconPx)
                : new LinearLayout.LayoutParams(UiKit.dp(ctx, 64), UiKit.dp(ctx, 32));
        indicator.setLayoutParams(ilp);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        indicator.addView(icon, new FrameLayout.LayoutParams(
                UiKit.dp(ctx, 24), UiKit.dp(ctx, 24), Gravity.CENTER));
        item.addView(indicator);

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, floating ? 11f : 12f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setMaxLines(1);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = UiKit.dp(ctx, miuix ? 2 : 4);
        item.addView(label, tlp);

        items[index] = item;
        icons[index] = icon;
        labels[index] = label;
        indicators[index] = indicator;
        addView(item);
    }

    public void setOnSelectListener(OnSelectListener l) {
        this.listener = l;
    }

    /** Highlights {@code index} without notifying the listener. */
    public void select(int index) {
        selected = index;
        Context ctx = getContext();
        for (int i = 0; i < items.length; i++) {
            boolean sel = i == index;
            if (!miuix) {
                indicators[i].setBackground(sel
                        ? UiKit.roundedRect(ctx, palette.secondaryContainer, 16f, 0, 0)
                        : UiKit.roundedRect(ctx, Color.TRANSPARENT, 16f, 0, 0));
                icons[i].setImageTintList(ColorStateList.valueOf(
                        sel ? palette.onSecondaryContainer : palette.onSurfaceVariant));
                labels[i].setTextColor(sel ? palette.onSurface : palette.onSurfaceVariant);
            }
            labels[i].setTypeface(Typeface.create("sans-serif-medium", sel ? Typeface.BOLD : Typeface.NORMAL));
        }
        if (miuix && !dragging) {
            if (indicatorPos < 0f || getWidth() == 0) {
                setIndicator(index);
            } else {
                animateIndicatorTo(index);
            }
        }
    }

    /**
     * Moves the Miuix selection to a fractional tab position, for example while the pages are
     * swiped. Ignored while the user drags across the bar.
     */
    public void setIndicatorPosition(float position) {
        if (!miuix || dragging) return;
        if (indicatorAnim != null) indicatorAnim.cancel();
        setIndicator(position);
    }

    private void setIndicator(float position) {
        float pos = Math.max(0f, Math.min(items.length - 1, position));
        if (pos == indicatorPos) return;
        indicatorPos = pos;
        applyMiuixColors();
        invalidate();
    }

    private void animateIndicatorTo(int index) {
        if (indicatorAnim != null) indicatorAnim.cancel();
        if (indicatorPos == index) return;
        indicatorAnim = ValueAnimator.ofFloat(indicatorPos, index);
        indicatorAnim.setDuration(320L);
        indicatorAnim.setInterpolator(UiKit.STANDARD_EASING);
        indicatorAnim.addUpdateListener(a -> setIndicator((float) a.getAnimatedValue()));
        indicatorAnim.start();
    }

    /** Icon and label colors follow the indicator, so they cross-fade while it slides. */
    private void applyMiuixColors() {
        int on = floating ? palette.primary : palette.onSurface;
        for (int i = 0; i < items.length; i++) {
            float f = Math.max(0f, 1f - Math.abs(i - indicatorPos));
            int fg = UiKit.blend(palette.onSurfaceVariant, on, f);
            labels[i].setTextColor(fg);
            icons[i].setImageTintList(ColorStateList.valueOf(fg));
        }
    }

    private void animatePress(float target) {
        if (pressAnim != null) pressAnim.cancel();
        pressAnim = ValueAnimator.ofFloat(pressProgress, target);
        pressAnim.setDuration(target > pressProgress ? 160L : 260L);
        pressAnim.setInterpolator(UiKit.STANDARD_EASING);
        pressAnim.addUpdateListener(a -> {
            pressProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        pressAnim.start();
    }

    @Override
    protected void onDrawBehindChildren(Canvas canvas) {
        if (!floating || indicatorPos < 0f || items.length == 0) return;
        // SukiSU floating bar: a neutral capsule behind the selected tab. While dragging it
        // grows a little and gets a light rim, like a glass droplet lifted off the bar.
        float itemW = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) items.length;
        float left = getPaddingLeft() + indicatorPos * itemW;
        float grow = UiKit.dpf(getContext(), 3f) * pressProgress;
        indicatorRect.set(left - grow, getPaddingTop() - grow,
                left + itemW + grow, getHeight() - getPaddingBottom() + grow);
        float r = indicatorRect.height() / 2f;
        int base = palette.isNight ? 0x1AFFFFFF : 0x1A000000;
        int lifted = palette.isNight ? 0x2EFFFFFF : 0x24000000;
        indicatorPaint.setColor(UiKit.blend(base, lifted, pressProgress));
        canvas.drawRoundRect(indicatorRect, r, r, indicatorPaint);
        if (pressProgress > 0.01f) {
            int rim = palette.isNight ? 0x59FFFFFF : 0x99FFFFFF;
            indicatorRimPaint.setColor(UiKit.withAlpha(rim, Math.round(Color.alpha(rim) * pressProgress)));
            float half = indicatorRimPaint.getStrokeWidth() / 2f;
            indicatorRect.inset(half, half);
            canvas.drawRoundRect(indicatorRect, r - half, r - half, indicatorRimPaint);
        }
    }

    // --- drag to switch tabs ------------------------------------------------------------------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (items.length < 2) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && startDragIfNeeded(e)) dragTo(e.getX());
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (items.length < 2) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) startDragIfNeeded(e);
                if (dragging) dragTo(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) endDrag();
                return true;
            default:
                return true;
        }
    }

    private boolean startDragIfNeeded(MotionEvent e) {
        float dx = e.getX() - downX;
        float dy = e.getY() - downY;
        if (Math.abs(dx) <= touchSlop || Math.abs(dx) <= Math.abs(dy)) return false;
        dragging = true;
        if (indicatorAnim != null) indicatorAnim.cancel();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        if (floating) animatePress(1f);
        return true;
    }

    private void dragTo(float x) {
        float itemW = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) items.length;
        if (itemW <= 0f) return;
        float pos = Math.max(0f, Math.min(items.length - 1, (x - getPaddingLeft()) / itemW - 0.5f));
        if (miuix) {
            indicatorPos = pos;
            applyMiuixColors();
            invalidate();
        }
        int index = Math.round(pos);
        if (index != selected) {
            select(index);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.onSelect(index);
        }
    }

    private void endDrag() {
        dragging = false;
        if (floating) animatePress(0f);
        if (miuix && selected >= 0) animateIndicatorTo(selected);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (indicatorAnim != null) indicatorAnim.cancel();
        if (pressAnim != null) pressAnim.cancel();
        super.onDetachedFromWindow();
    }
}
