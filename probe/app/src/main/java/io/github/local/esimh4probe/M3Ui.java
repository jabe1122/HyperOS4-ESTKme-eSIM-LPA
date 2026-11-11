package io.github.local.esimh4probe;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.Checkable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Pure OS-native Material Design 3 Expressive / Material You Dynamic Color UI toolkit
 * for Android 13–17+ (HyperOS 4), requiring zero external AAR dependencies.
 */
final class M3Ui {
    static final PathInterpolator M3_STANDARD_EASING = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final ArgbEvaluator ARGB_EVAL = new ArgbEvaluator();

    private M3Ui() {}

    static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    static float dpf(Context ctx, float value) {
        return value * ctx.getResources().getDisplayMetrics().density;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    static int blend(int fromColor, int toColor, float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        return (int) ARGB_EVAL.evaluate(clamped, fromColor, toColor);
    }

    static void applyEdgeToEdge(Activity activity, Palette palette) {
        Window window = activity.getWindow();
        if (window == null) return;
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        View decor = window.getDecorView();
        WindowInsetsController ctrl = decor != null ? decor.getWindowInsetsController() : null;
        if (ctrl != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(palette.isNight ? 0 : mask, mask);
        }
    }

    /**
     * Resolves Android 14–17 native Material 3 Dynamic Color (Monet) tokens from framework resources,
     * with harmonious M3 baseline fallback on Android 13 or non-Monet builds.
     */
    static final class Palette {
        final boolean isNight;
        final int pageBg;
        final int cardBg;
        final int tileBg;
        final int surfaceHigh;
        final int surfaceHighest;
        final int primary;
        final int onPrimary;
        final int primaryContainer;
        final int onPrimaryContainer;
        final int secondaryContainer;
        final int onSecondaryContainer;
        final int error;
        final int errorContainer;
        final int onErrorContainer;
        final int onSurface;
        final int onSurfaceVariant;
        final int outline;
        final int outlineVariant;
        final int ripple;
        final int inverseSurface;
        final int inverseOnSurface;

        private Palette(Context ctx) {
            int nightFlags = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            this.isNight = (nightFlags == Configuration.UI_MODE_NIGHT_YES);

            if (!isNight) {
                pageBg = sysColor(ctx, "system_surface_container_low_light", 0xFFF3F4F9);
                cardBg = sysColor(ctx, "system_surface_container_lowest_light", 0xFFFFFFFF);
                tileBg = sysColor(ctx, "system_surface_container_light", 0xFFECEEF4);
                surfaceHigh = sysColor(ctx, "system_surface_container_high_light", 0xFFE6E8EE);
                surfaceHighest = sysColor(ctx, "system_surface_container_highest_light", 0xFFE0E2E8);
                primary = sysColor(ctx, "system_primary_light", 0xFF1B5E9E);
                onPrimary = sysColor(ctx, "system_on_primary_light", 0xFFFFFFFF);
                primaryContainer = sysColor(ctx, "system_primary_container_light", 0xFFD4E3FF);
                onPrimaryContainer = sysColor(ctx, "system_on_primary_container_light", 0xFF001C3A);
                secondaryContainer = sysColor(ctx, "system_secondary_container_light", 0xFFD7E3F8);
                onSecondaryContainer = sysColor(ctx, "system_on_secondary_container_light", 0xFF101C2B);
                error = sysColor(ctx, "system_error_light", 0xFFBA1A1A);
                errorContainer = sysColor(ctx, "system_error_container_light", 0xFFFFDAD6);
                onErrorContainer = sysColor(ctx, "system_on_error_container_light", 0xFF410002);
                onSurface = sysColor(ctx, "system_on_surface_light", 0xFF191C20);
                onSurfaceVariant = sysColor(ctx, "system_on_surface_variant_light", 0xFF43474E);
                outline = sysColor(ctx, "system_outline_light", 0xFF73777F);
                outlineVariant = sysColor(ctx, "system_outline_variant_light", 0xFFC3C6CF);
                inverseSurface = 0xFF2E3035;
                inverseOnSurface = 0xFFF0F0F7;
                ripple = withAlpha(primary, 0x1F);
            } else {
                pageBg = sysColor(ctx, "system_surface_container_lowest_dark", 0xFF0C0E13);
                cardBg = sysColor(ctx, "system_surface_container_dark", 0xFF1B1F26);
                tileBg = sysColor(ctx, "system_surface_container_high_dark", 0xFF252A32);
                surfaceHigh = sysColor(ctx, "system_surface_container_high_dark", 0xFF262A32);
                surfaceHighest = sysColor(ctx, "system_surface_container_highest_dark", 0xFF31353E);
                primary = sysColor(ctx, "system_primary_dark", 0xFFA5C8FF);
                onPrimary = sysColor(ctx, "system_on_primary_dark", 0xFF00315E);
                primaryContainer = sysColor(ctx, "system_primary_container_dark", 0xFF004785);
                onPrimaryContainer = sysColor(ctx, "system_on_primary_container_dark", 0xFFD4E3FF);
                secondaryContainer = sysColor(ctx, "system_secondary_container_dark", 0xFF3B4858);
                onSecondaryContainer = sysColor(ctx, "system_on_secondary_container_dark", 0xFFD7E3F8);
                error = sysColor(ctx, "system_error_dark", 0xFFFFB4AB);
                errorContainer = sysColor(ctx, "system_error_container_dark", 0xFF93000A);
                onErrorContainer = sysColor(ctx, "system_on_error_container_dark", 0xFFFFDAD6);
                onSurface = sysColor(ctx, "system_on_surface_dark", 0xFFE1E2E8);
                onSurfaceVariant = sysColor(ctx, "system_on_surface_variant_dark", 0xFFC3C6CF);
                outline = sysColor(ctx, "system_outline_dark", 0xFF8D9199);
                outlineVariant = sysColor(ctx, "system_outline_variant_dark", 0xFF43474E);
                inverseSurface = 0xFFE2E2E9;
                inverseOnSurface = 0xFF191C20;
                ripple = withAlpha(primary, 0x29);
            }
        }

        static Palette resolve(Context ctx) {
            return new Palette(ctx);
        }

        private static int sysColor(Context ctx, String name, int fallback) {
            if (Build.VERSION.SDK_INT < 34) {
                return fallback;
            }
            try {
                Resources res = ctx.getResources();
                int id = res.getIdentifier(name, "color", "android");
                if (id != 0) {
                    return ctx.getColor(id);
                }
            } catch (Throwable ignored) {
            }
            return fallback;
        }
    }

    static GradientDrawable roundedRect(Context ctx, int fillColor, float radiusDp, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dpf(ctx, radiusDp));
        gd.setColor(fillColor);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    static GradientDrawable segmentedRect(
            Context ctx,
            int fillColor,
            float topDp,
            float bottomDp,
            int strokeWidthPx,
            int strokeColor) {
        float t = dpf(ctx, topDp);
        float b = dpf(ctx, bottomDp);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        gd.setColor(fillColor);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    static Drawable rippleSegmented(
            Context ctx,
            int fillColor,
            int rippleColor,
            float topDp,
            float bottomDp,
            int strokeWidthPx,
            int strokeColor) {
        GradientDrawable content = segmentedRect(ctx, fillColor, topDp, bottomDp, strokeWidthPx, strokeColor);
        GradientDrawable mask = segmentedRect(ctx, Color.WHITE, topDp, bottomDp, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static Drawable oval(int fillColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(fillColor);
        return gd;
    }

    static Drawable rippleOval(int fillColor, int rippleColor) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.OVAL);
        content.setColor(fillColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static ImageView createIconButton(Context ctx, Palette p, int iconRes, String contentDesc, Runnable onClick) {
        ImageView btn = new ImageView(ctx);
        int size = dp(ctx, 42);
        int pad = dp(ctx, 9);
        btn.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        btn.setPadding(pad, pad, pad, pad);
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.setImageResource(iconRes);
        btn.setImageTintList(ColorStateList.valueOf(p.onSurfaceVariant));
        btn.setBackground(rippleOval(p.tileBg, p.ripple));
        btn.setContentDescription(contentDesc);
        if (Build.VERSION.SDK_INT >= 26) {
            btn.setTooltipText(contentDesc);
        }
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    static TextView createSectionHeader(Context ctx, Palette p, String title) {
        TextView tv = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 18);
        lp.bottomMargin = dp(ctx, 8);
        lp.leftMargin = dp(ctx, 6);
        lp.rightMargin = dp(ctx, 6);
        tv.setLayoutParams(lp);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(p.primary);
        return tv;
    }

    enum ButtonStyle {
        FILLED,
        TONAL,
        OUTLINED
    }

    static LinearLayout createActionButton(
            Context ctx,
            Palette p,
            ButtonStyle style,
            int iconRes,
            String text,
            int heightDp,
            Runnable onClick) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setMinimumHeight(dp(ctx, heightDp));
        int hPad = dp(ctx, 16);
        int vPad = dp(ctx, 10);
        box.setPadding(hPad, vPad, hPad, vPad);

        int bgColor;
        int fgColor;
        int strokePx = 0;
        int strokeColor = 0;
        if (style == ButtonStyle.FILLED) {
            bgColor = p.primary;
            fgColor = p.onPrimary;
        } else if (style == ButtonStyle.TONAL) {
            bgColor = p.secondaryContainer;
            fgColor = p.onSecondaryContainer;
        } else {
            bgColor = Color.TRANSPARENT;
            fgColor = p.primary;
            strokePx = Math.max(1, dp(ctx, 1));
            strokeColor = p.outlineVariant;
        }

        float radiusDp = heightDp / 2f;
        int ripple = style == ButtonStyle.FILLED ? withAlpha(p.onPrimary, 0x26) : p.ripple;
        box.setBackground(rippleSegmented(ctx, bgColor, ripple, radiusDp, radiusDp, strokePx, strokeColor));

        if (iconRes != 0) {
            ImageView iv = new ImageView(ctx);
            int iconPx = dp(ctx, 18);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconPx, iconPx);
            ilp.rightMargin = dp(ctx, 8);
            iv.setLayoutParams(ilp);
            iv.setImageResource(iconRes);
            iv.setImageTintList(ColorStateList.valueOf(fgColor));
            box.addView(iv);
        }

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(fgColor);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(tv);

        box.setClickable(true);
        box.setFocusable(true);
        box.setOnClickListener(v -> {
            if (box.isEnabled()) {
                onClick.run();
            }
        });
        return box;
    }

    static void setButtonEnabled(View btn, boolean enabled) {
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1.0f : 0.42f);
    }

    /**
     * Segmented Material 3 Expressive Settings Switch Card with leading tonal icon circle,
     * title + dynamic subtitle, and bespoke animated M3 pill switch.
     */
    static final class SwitchRow {
        final LinearLayout card;
        final FrameLayout iconWrap;
        final ImageView iconView;
        final TextView titleView;
        final TextView subtitleView;
        final M3Switch toggle;
        private final Palette palette;

        SwitchRow(
                Context ctx,
                Palette p,
                int iconRes,
                String title,
                String initialSub,
                float topRadiusDp,
                float bottomRadiusDp,
                int bottomGapDp) {
            this.palette = p;
            card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(ctx, bottomGapDp);
            card.setLayoutParams(clp);
            int padH = dp(ctx, 16);
            int padV = dp(ctx, 15);
            card.setPadding(padH, padV, padH, padV);
            card.setBackground(rippleSegmented(ctx, p.cardBg, p.ripple, topRadiusDp, bottomRadiusDp, 0, 0));
            card.setClickable(true);
            card.setFocusable(true);

            iconWrap = new FrameLayout(ctx);
            int boxSize = dp(ctx, 40);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(boxSize, boxSize);
            ilp.rightMargin = dp(ctx, 14);
            iconWrap.setLayoutParams(ilp);

            iconView = new ImageView(ctx);
            int iconSize = dp(ctx, 21);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER);
            iconView.setLayoutParams(flp);
            iconView.setImageResource(iconRes);
            iconWrap.addView(iconView);
            card.addView(iconWrap);

            LinearLayout textCol = new LinearLayout(ctx);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.rightMargin = dp(ctx, 12);
            textCol.setLayoutParams(tlp);

            titleView = new TextView(ctx);
            titleView.setText(title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            titleView.setTextColor(p.onSurface);
            textCol.addView(titleView);

            subtitleView = new TextView(ctx);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(ctx, 3);
            subtitleView.setLayoutParams(slp);
            subtitleView.setText(initialSub);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            subtitleView.setLineSpacing(dpf(ctx, 2f), 1.0f);
            subtitleView.setTextColor(p.onSurfaceVariant);
            textCol.addView(subtitleView);

            card.addView(textCol);

            toggle = new M3Switch(ctx, p);
            card.addView(toggle);
            refreshIconTint(false);
        }

        void refreshIconTint(boolean checked) {
            int wrapBg = checked ? palette.secondaryContainer : palette.tileBg;
            int tint = checked ? palette.onSecondaryContainer : palette.onSurfaceVariant;
            iconWrap.setBackground(oval(wrapBg));
            iconView.setImageTintList(ColorStateList.valueOf(tint));
        }

        void setRowState(boolean checked, boolean enabled, String subtitle) {
            toggle.setChecked(checked, false);
            toggle.setEnabled(enabled);
            subtitleView.setText(subtitle);
            titleView.setAlpha(enabled ? 1.0f : 0.62f);
            subtitleView.setAlpha(enabled ? 1.0f : 0.58f);
            iconWrap.setAlpha(enabled ? 1.0f : 0.55f);
            refreshIconTint(checked && enabled);
        }
    }

    /**
     * Bespoke Material Design 3 Pill Switch (52dp x 32dp) with M3 spatial easing,
     * tactile thumb expansion on press, and crisp anti-aliased ✓ / ✕ thumb icons.
     */
    static final class M3Switch extends View implements Checkable {
        interface OnCheckedChangeListener {
            void onCheckedChanged(M3Switch view, boolean isChecked);
        }

        private final Palette palette;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();

        private boolean checked = false;
        private float progress = 0f;
        private float pressProgress = 0f;
        private ValueAnimator checkAnimator;
        private ValueAnimator pressAnimator;
        private OnCheckedChangeListener listener;

        M3Switch(Context ctx, Palette palette) {
            super(ctx);
            this.palette = palette;
            setClickable(true);
            setFocusable(true);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dpf(ctx, 2f));

            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            glyphPaint.setStrokeJoin(Paint.Join.ROUND);
            glyphPaint.setStrokeWidth(dpf(ctx, 1.9f));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 52), dp(ctx, 32));
            setLayoutParams(lp);
        }

        void setOnCheckedChangeListener(OnCheckedChangeListener l) {
            this.listener = l;
        }

        @Override
        public void setChecked(boolean value) {
            setChecked(value, isAttachedToWindow());
        }

        void setChecked(boolean value, boolean animate) {
            boolean changed = (this.checked != value);
            this.checked = value;
            if (checkAnimator != null) {
                checkAnimator.cancel();
                checkAnimator = null;
            }
            float target = value ? 1f : 0f;
            if (animate && changed) {
                checkAnimator = ValueAnimator.ofFloat(progress, target);
                checkAnimator.setDuration(190L);
                checkAnimator.setInterpolator(M3_STANDARD_EASING);
                checkAnimator.addUpdateListener(anim -> {
                    progress = (float) anim.getAnimatedValue();
                    invalidate();
                });
                checkAnimator.start();
            } else {
                progress = target;
                invalidate();
            }
            if (changed) {
                sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            }
        }

        @Override
        public boolean isChecked() {
            return checked;
        }

        @Override
        public void toggle() {
            performUserToggle();
        }

        void performUserToggle() {
            if (!isEnabled()) return;
            boolean next = !checked;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            setChecked(next, true);
            if (listener != null) {
                listener.onCheckedChanged(this, next);
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            performUserToggle();
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                animatePress(1f);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                animatePress(0f);
            }
            return super.onTouchEvent(event);
        }

        private void animatePress(float target) {
            if (pressAnimator != null) {
                pressAnimator.cancel();
            }
            pressAnimator = ValueAnimator.ofFloat(pressProgress, target);
            pressAnimator.setDuration(110L);
            pressAnimator.setInterpolator(M3_STANDARD_EASING);
            pressAnimator.addUpdateListener(a -> {
                pressProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            pressAnimator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = dp(getContext(), 52);
            int h = dp(getContext(), 32);
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float strokeHalf = borderPaint.getStrokeWidth() / 2f;

            int alphaLayer = isEnabled() ? 255 : 97; // 0.38f official M3 disabled opacity
            if (alphaLayer < 255) {
                canvas.saveLayerAlpha(0, 0, w, h, alphaLayer);
            }

            // 1. Track pill
            int trackColor = blend(palette.surfaceHighest, palette.primary, progress);
            trackPaint.setColor(trackColor);
            trackRect.set(strokeHalf, strokeHalf, w - strokeHalf, h - strokeHalf);
            float trackRadius = trackRect.height() / 2f;
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint);

            // 2. Unchecked 2dp outline border
            int borderAlpha = Math.round((1f - progress) * 255f);
            if (borderAlpha > 4) {
                borderPaint.setColor(withAlpha(palette.outline, borderAlpha));
                canvas.drawRoundRect(trackRect, trackRadius, trackRadius, borderPaint);
            }

            // 3. Thumb position & dynamic M3 radius (24dp resting -> 28dp pressed)
            float startCx = h / 2f;
            float endCx = w - (h / 2f);
            float cx = startCx + (endCx - startCx) * progress;
            float cy = h / 2f;
            float baseRadius = dpf(getContext(), 12f);
            float pressedDelta = dpf(getContext(), 2f) * pressProgress;
            float thumbRadius = baseRadius + pressedDelta;

            int thumbColor = blend(palette.outline, palette.onPrimary, progress);
            thumbPaint.setColor(thumbColor);
            canvas.drawCircle(cx, cy, thumbRadius, thumbPaint);

            // 4. Crisp M3 thumb icon (✕ when unchecked, ✓ when checked)
            if (progress < 0.5f) {
                float iconAlpha = (0.5f - progress) * 2f;
                glyphPaint.setColor(withAlpha(palette.surfaceHighest, Math.round(iconAlpha * 255f)));
                float d = dpf(getContext(), 3.8f) * (0.85f + 0.15f * iconAlpha);
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, glyphPaint);
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, glyphPaint);
            } else {
                float iconAlpha = (progress - 0.5f) * 2f;
                glyphPaint.setColor(withAlpha(palette.primary, Math.round(iconAlpha * 255f)));
                float s = dpf(getContext(), 1f) * (0.85f + 0.15f * iconAlpha);
                canvas.drawLine(cx - 4.6f * s, cy + 0.2f * s, cx - 1.3f * s, cy + 3.5f * s, glyphPaint);
                canvas.drawLine(cx - 1.3f * s, cy + 3.5f * s, cx + 5.0f * s, cy - 3.2f * s, glyphPaint);
            }

            if (alphaLayer < 255) {
                canvas.restore();
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    /**
     * Shows a Material Design 3 28dp Extra-Large Rounded Dialog with optional neutral copy button.
     */
    static void showM3Dialog(
            Activity activity,
            Palette p,
            String title,
            String message,
            boolean monospaceBody,
            String neutralText,
            Runnable onNeutral,
            String positiveText) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        root.setPadding(pad, pad, pad, dp(activity, 18));
        root.setBackground(roundedRect(activity, p.surfaceHigh, 28f, 0, 0));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setTextColor(p.onSurface);
        root.addView(tvTitle);

        final int maxScrollHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.56f);
        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int cappedSpec = MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, cappedSpec);
            }
        };
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(activity, 14);
        slp.bottomMargin = dp(activity, 18);
        scroll.setLayoutParams(slp);

        TextView tvMsg = new TextView(activity);
        tvMsg.setText(message);
        tvMsg.setTextIsSelectable(true);
        if (monospaceBody) {
            int codePad = dp(activity, 12);
            tvMsg.setPadding(codePad, codePad, codePad, codePad);
            tvMsg.setBackground(roundedRect(activity, p.pageBg, 14f, 0, 0));
            tvMsg.setTypeface(Typeface.MONOSPACE);
            tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvMsg.setTextColor(p.onSurface);
        } else {
            tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            tvMsg.setLineSpacing(dpf(activity, 3f), 1.0f);
            tvMsg.setTextColor(p.onSurfaceVariant);
        }
        scroll.addView(tvMsg);
        root.addView(scroll);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        if (neutralText != null && onNeutral != null) {
            LinearLayout btnNeutral = createActionButton(
                    activity, p, ButtonStyle.TONAL, 0, neutralText, 40, () -> {
                        onNeutral.run();
                        dialog.dismiss();
                    });
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.rightMargin = dp(activity, 10);
            btnNeutral.setLayoutParams(nlp);
            actions.addView(btnNeutral);
        }

        LinearLayout btnPos = createActionButton(
                activity, p, ButtonStyle.FILLED, 0, positiveText != null ? positiveText : "确定", 40, dialog::dismiss);
        btnPos.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(btnPos);
        root.addView(actions);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int maxW = Math.min(screenW - dp(activity, 36), dp(activity, 420));
            w.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }
}
