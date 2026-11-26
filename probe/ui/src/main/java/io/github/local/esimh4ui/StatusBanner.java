package io.github.local.esimh4ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * Home status card, following SukiSU-Nova.
 *
 * <ul>
 *   <li>Working (Miuix): light green card, large green check outline in the corner, small pill
 *       tags at the top, 36sp bold title and a monospace version line at the bottom.</li>
 *   <li>Working (Material 3): primary card with ring decoration and inverse tags.</li>
 *   <li>Problem: plain card (error container in Material 3) with a small red hint line, a 36sp
 *       title and an action button.</li>
 * </ul>
 */
public final class StatusBanner extends FrameLayout {
    /** Pill tag; {@code accent} tags use the solid accent color. */
    public static final class Tag {
        final String label;
        final boolean accent;

        public Tag(String label, boolean accent) {
            this.label = label;
            this.accent = accent;
        }
    }

    private enum Mode {
        LOADING,
        OK,
        ERROR
    }

    private final UiKit.Palette palette;
    private final boolean miuix;
    private final ImageView decor;
    private final LinearLayout content;
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PressFeedback pressFeedback = new PressFeedback(this);
    private Mode mode = Mode.LOADING;

    public StatusBanner(Context ctx, UiKit.Palette p) {
        super(ctx);
        this.palette = p;
        this.miuix = UiKit.isMiuix();
        setClipToOutline(true);
        setWillNotDraw(false);
        // A real card: sinks and tilts under the finger, with a ripple on top.
        setClickable(true);
        setForeground(new RippleDrawable(
                ColorStateList.valueOf(UiKit.withAlpha(p.onSurface, 0x1F)), null,
                UiKit.roundedRect(ctx, 0xFF000000, UiKit.radius(28f, 16f), 0, 0)));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(UiKit.dpf(ctx, 1.5f));

        decor = new ImageView(ctx);
        int decorPx = UiKit.dp(ctx, 150);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(decorPx, decorPx, Gravity.END | Gravity.BOTTOM);
        dlp.rightMargin = -UiKit.dp(ctx, 27);
        dlp.bottomMargin = -UiKit.dp(ctx, 31);
        addView(decor, dlp);

        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setMinimumHeight(UiKit.dp(ctx, 188));
        int pad = UiKit.dp(ctx, 22);
        content.setPadding(pad, pad, pad, pad);
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isEnabled() && hasOnClickListeners()) pressFeedback.onTouchEvent(e);
        return super.onTouchEvent(e);
    }

    private boolean dynamic() {
        return ThemeConfig.dynamicColor;
    }

    // --- colors -------------------------------------------------------------------------------

    private int okBackground() {
        if (!miuix) return palette.primary;
        if (dynamic()) return palette.primaryContainer;
        return palette.isNight ? 0xFF1A3825 : 0xFFDFFAE4;
    }

    private int okAccent() {
        if (!miuix) return palette.onPrimary;
        return dynamic() ? palette.primary : 0xFF36D167;
    }

    private int okTitleColor() {
        return miuix ? palette.onSurface : palette.onPrimary;
    }

    private int okSummaryColor() {
        return miuix ? palette.onSurfaceVariant : UiKit.withAlpha(palette.onPrimary, 0xCC);
    }

    // --- states -------------------------------------------------------------------------------

    public void showLoading(String title) {
        mode = Mode.LOADING;
        content.removeAllViews();
        decor.setVisibility(GONE);
        setBackground(UiKit.roundedRect(getContext(), miuix ? palette.cardBg : palette.surfaceHigh,
                UiKit.radius(28f, 16f), 0, 0));
        content.addView(flexSpace());
        content.addView(bigTitle(title, palette.onSurface));
        invalidate();
    }

    public void showOk(String title, String version, List<Tag> tags) {
        mode = Mode.OK;
        content.removeAllViews();
        setBackground(UiKit.roundedRect(getContext(), okBackground(), UiKit.radius(28f, 16f), 0, 0));

        if (miuix) {
            decor.setVisibility(VISIBLE);
            decor.setImageResource(R.drawable.ic_check_circle_outline_24);
            decor.setImageTintList(ColorStateList.valueOf(okAccent()));
            decor.setAlpha(dynamic() ? 0.8f : 1f);
        } else {
            decor.setVisibility(GONE); // Material 3 draws rings instead
        }

        LinearLayout tagRow = new LinearLayout(getContext());
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        for (Tag tag : tags) {
            tagRow.addView(tagView(tag));
        }
        content.addView(tagRow);
        content.addView(flexSpace());
        content.addView(bigTitle(title, okTitleColor()));
        TextView v = new TextView(getContext());
        v.setText(version);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextColor(okSummaryColor());
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = UiKit.dp(getContext(), miuix ? 4 : 6);
        content.addView(v, vlp);
        invalidate();
    }

    public void showError(String hint, String title, String actionText, Runnable action) {
        mode = Mode.ERROR;
        content.removeAllViews();
        decor.setVisibility(GONE);
        Context ctx = getContext();
        int bg = miuix ? palette.cardBg : palette.errorContainer;
        int accent = miuix ? palette.error : palette.onErrorContainer;
        int titleColor = miuix ? palette.onSurface : palette.onErrorContainer;
        setBackground(UiKit.roundedRect(ctx, bg, UiKit.radius(28f, 16f), 0, 0));

        LinearLayout hintRow = new LinearLayout(ctx);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_error_outline_24);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        int is = UiKit.dp(ctx, 18);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(is, is);
        ilp.rightMargin = UiKit.dp(ctx, 6);
        hintRow.addView(icon, ilp);
        TextView tvHint = new TextView(ctx);
        tvHint.setText(hint);
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvHint.setTextColor(accent);
        hintRow.addView(tvHint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(hintRow);

        content.addView(flexSpace());
        TextView t = bigTitle(title, titleColor);
        t.setPadding(0, UiKit.dp(ctx, 12), 0, UiKit.dp(ctx, 12));
        content.addView(t);

        if (actionText != null && action != null) {
            LinearLayout btn = UiKit.createActionButton(ctx, palette, UiKit.ButtonStyle.FILLED, 0, actionText, 40, action);
            content.addView(btn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        invalidate();
    }

    // --- pieces -------------------------------------------------------------------------------

    private View flexSpace() {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        return v;
    }

    private TextView bigTitle(String text, int color) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f);
        t.setTypeface(miuix ? Typeface.DEFAULT_BOLD : Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** SukiSU StatusTag: 6dp corners, 4/2dp padding, 9sp heavy text (11sp in Material 3). */
    private TextView tagView(Tag tag) {
        Context ctx = getContext();
        TextView tv = new TextView(ctx);
        tv.setText(tag.label);
        tv.setSingleLine(true);
        int bg;
        int fg;
        if (!miuix) {
            bg = tag.accent ? palette.onPrimary : UiKit.withAlpha(palette.onPrimary, 0x33);
            fg = tag.accent ? palette.primary : palette.onPrimary;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tv.setPadding(UiKit.dp(ctx, 8), UiKit.dp(ctx, 3), UiKit.dp(ctx, 8), UiKit.dp(ctx, 3));
        } else {
            if (tag.accent) {
                bg = okAccent();
                fg = dynamic() ? palette.onPrimary : 0xFFFFFFFF;
            } else if (dynamic()) {
                bg = palette.secondaryContainer;
                fg = palette.onSecondaryContainer;
            } else {
                bg = palette.isNight ? 0xFF315D3E : 0xFFB8E8C5;
                fg = palette.isNight ? 0xFFB8E8C5 : 0xFF164A29;
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
            tv.setPadding(UiKit.dp(ctx, 4), UiKit.dp(ctx, 2), UiKit.dp(ctx, 4), UiKit.dp(ctx, 2));
        }
        tv.setTypeface(Typeface.create(Typeface.DEFAULT, 750, false));
        tv.setTextColor(fg);
        tv.setBackground(UiKit.roundedRect(ctx, bg, miuix ? 6f : 8f, 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UiKit.dp(ctx, miuix ? 6 : 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mode == Mode.OK && !miuix) {
            // Material 3 status rings in the lower right corner.
            ringPaint.setColor(UiKit.withAlpha(palette.onPrimary, 0x2E));
            float cx = getWidth() - UiKit.dpf(getContext(), 24f);
            float cy = getHeight() - UiKit.dpf(getContext(), 20f);
            for (int i = 1; i <= 4; i++) {
                canvas.drawCircle(cx, cy, UiKit.dpf(getContext(), 34f * i), ringPaint);
            }
        }
    }
}
