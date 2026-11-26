package io.github.local.esimh4ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Outline;
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
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
 * Dependency-free view toolkit with two selectable design languages: Material 3 Expressive
 * (Monet dynamic color) and Miuix (HyperOS), mirroring the UI style choice of SukiSU managers.
 */
public final class UiKit {
    public static final PathInterpolator STANDARD_EASING = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final ArgbEvaluator ARGB_EVAL = new ArgbEvaluator();

    private UiKit() {}

    /** Selectable design language. */
    public enum Style {
        MATERIAL3,
        MIUIX
    }

    public static boolean isMiuix() {
        return ThemeConfig.style == Style.MIUIX;
    }

    /** Picks the corner radius (dp) for the active style. */
    public static float radius(float material3Dp, float miuixDp) {
        return isMiuix() ? miuixDp : material3Dp;
    }

    public static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    public static float dpf(Context ctx, float value) {
        return value * ctx.getResources().getDisplayMetrics().density;
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static int blend(int fromColor, int toColor, float fraction) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        return (int) ARGB_EVAL.evaluate(clamped, fromColor, toColor);
    }

    public static void applyEdgeToEdge(Activity activity, Palette palette) {
        Window window = activity.getWindow();
        if (window == null) return;
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);
        View decor = window.getDecorView();
        WindowInsetsController ctrl = decor != null ? decor.getWindowInsetsController() : null;
        if (ctrl != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(palette.isNight ? 0 : mask, mask);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Palette
    // ---------------------------------------------------------------------------------------------

    /**
     * Color tokens for the active style. Material 3 reads the Android 14+ Monet tokens; Miuix uses
     * the HyperOS scheme, optionally tinted with Monet when dynamic color is on.
     */
    public static final class Palette {
        public final boolean isNight;
        public final int pageBg;
        public final int cardBg;
        public final int tileBg;
        public final int surfaceHigh;
        public final int surfaceHighest;
        public final int primary;
        public final int onPrimary;
        public final int primaryContainer;
        public final int onPrimaryContainer;
        public final int secondaryContainer;
        public final int onSecondaryContainer;
        public final int error;
        public final int errorContainer;
        public final int onErrorContainer;
        public final int onSurface;
        public final int onSurfaceVariant;
        public final int outline;
        public final int outlineVariant;
        public final int ripple;
        public final int inverseSurface;
        public final int inverseOnSurface;
        public final int sectionTitle;
        /** Green "working" colors for status cards (SukiSU-style red / green states). */
        public final int success;
        public final int successContainer;
        public final int onSuccessContainer;
        /** True when a custom background image is shown behind the pages. */
        public final boolean hasBackground;

        private Palette(Context ctx) {
            int nightFlags = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            this.isNight = (nightFlags == Configuration.UI_MODE_NIGHT_YES);
            this.hasBackground = ThemeConfig.hasBackground(ctx);
            int cardBase;
            int tileBase;

            if (isMiuix() && !isNight) {
                pageBg = 0xFFF7F7F7;
                cardBase = 0xFFFFFFFF;
                tileBase = 0xFFF7F7F7;
                surfaceHigh = 0xFFFFFFFF;
                surfaceHighest = 0xFFE6E6E6;
                primary = sysColor(ctx, "system_primary_light", 0xFF3482FF);
                onPrimary = 0xFFFFFFFF;
                primaryContainer = sysColor(ctx, "system_primary_container_light", 0xFFE6F0FF);
                onPrimaryContainer = sysColor(ctx, "system_on_primary_container_light", 0xFF1F66D6);
                secondaryContainer = 0xFFF0F0F0;
                onSecondaryContainer = 0xFF000000;
                error = 0xFFE94634;
                errorContainer = 0xFFFDEDEB;
                onErrorContainer = 0xFFC33A2B;
                onSurface = 0xFF000000;
                onSurfaceVariant = 0xFF999999;
                outline = 0xFFB5B5B5;
                outlineVariant = 0xFFE0E0E0;
                inverseSurface = 0xF0303030;
                inverseOnSurface = 0xFFFFFFFF;
                ripple = 0x14000000;
                sectionTitle = 0xFF8C93B0;
                success = 0xFF2FB35A;
                successContainer = 0xFFE3F6E9;
                onSuccessContainer = 0xFF16793A;
            } else if (isMiuix()) {
                pageBg = 0xFF000000;
                cardBase = 0xFF242424;
                tileBase = 0xFF2D2D2D;
                surfaceHigh = 0xFF242424;
                surfaceHighest = 0xFF474747;
                primary = sysColor(ctx, "system_primary_dark", 0xFF277AF7);
                onPrimary = sysColor(ctx, "system_on_primary_dark", 0xFFFFFFFF);
                primaryContainer = sysColor(ctx, "system_primary_container_dark", 0xFF15233B);
                onPrimaryContainer = sysColor(ctx, "system_on_primary_container_dark", 0xFF8AB6FF);
                secondaryContainer = 0xFF333333;
                onSecondaryContainer = 0xFFE6E6E6;
                error = 0xFFF2584A;
                errorContainer = 0xFF3A1A17;
                onErrorContainer = 0xFFFF8A80;
                onSurface = 0xFFF2F2F2;
                onSurfaceVariant = 0xFF8C8C8C;
                outline = 0xFF6B6B6B;
                outlineVariant = 0xFF333333;
                inverseSurface = 0xF0E6E6E6;
                inverseOnSurface = 0xFF1A1A1A;
                ripple = 0x1AFFFFFF;
                sectionTitle = 0xFF787E96;
                success = 0xFF2A9D52;
                successContainer = 0xFF173A24;
                onSuccessContainer = 0xFF8FE0AC;
            } else if (!isNight) {
                pageBg = sysColor(ctx, "system_surface_container_low_light", 0xFFF3F4F9);
                cardBase = sysColor(ctx, "system_surface_container_lowest_light", 0xFFFFFFFF);
                tileBase = sysColor(ctx, "system_surface_container_light", 0xFFECEEF4);
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
                sectionTitle = primary;
                success = 0xFF2E7D45;
                successContainer = 0xFFC4EED0;
                onSuccessContainer = 0xFF00210E;
            } else {
                pageBg = ThemeConfig.amoledBackground ? 0xFF000000
                        : sysColor(ctx, "system_surface_container_lowest_dark", 0xFF0C0E13);
                cardBase = sysColor(ctx, "system_surface_container_dark", 0xFF1B1F26);
                tileBase = sysColor(ctx, "system_surface_container_high_dark", 0xFF252A32);
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
                sectionTitle = primary;
                success = 0xFF8FD6A2;
                successContainer = 0xFF1B4D2E;
                onSuccessContainer = 0xFFB4F1C6;
            }

            if (hasBackground) {
                int a = Math.round(Math.max(0.4f, Math.min(1f, ThemeConfig.cardAlpha / 100f)) * 255f);
                cardBg = withAlpha(cardBase, a);
                tileBg = withAlpha(tileBase, a);
            } else {
                cardBg = cardBase;
                tileBg = tileBase;
            }
        }

        public static Palette resolve(Context ctx) {
            return new Palette(ctx);
        }

        private static int sysColor(Context ctx, String name, int fallback) {
            if (Build.VERSION.SDK_INT < 34 || !ThemeConfig.dynamicColor) {
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

    // ---------------------------------------------------------------------------------------------
    // Drawables
    // ---------------------------------------------------------------------------------------------

    public static GradientDrawable roundedRect(Context ctx, int fillColor, float radiusDp, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dpf(ctx, radiusDp));
        gd.setColor(fillColor);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    public static GradientDrawable segmentedRect(
            Context ctx, int fillColor, float topDp, float bottomDp, int strokeWidthPx, int strokeColor) {
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

    public static Drawable rippleSegmented(
            Context ctx, int fillColor, int rippleColor, float topDp, float bottomDp, int strokeWidthPx, int strokeColor) {
        GradientDrawable content = segmentedRect(ctx, fillColor, topDp, bottomDp, strokeWidthPx, strokeColor);
        GradientDrawable mask = segmentedRect(ctx, Color.WHITE, topDp, bottomDp, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    public static Drawable oval(int fillColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(fillColor);
        return gd;
    }

    public static Drawable rippleOval(int fillColor, int rippleColor) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.OVAL);
        content.setColor(fillColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    // ---------------------------------------------------------------------------------------------
    // Basic components
    // ---------------------------------------------------------------------------------------------

    public static ImageView createIconButton(Context ctx, Palette p, int iconRes, String contentDesc, Runnable onClick) {
        ImageView btn = new ImageView(ctx);
        int size = dp(ctx, 42);
        int pad = dp(ctx, 9);
        btn.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        btn.setPadding(pad, pad, pad, pad);
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.setImageResource(iconRes);
        if (isMiuix()) {
            btn.setImageTintList(ColorStateList.valueOf(p.onSurface));
            btn.setBackground(rippleOval(Color.TRANSPARENT, p.ripple));
        } else {
            btn.setImageTintList(ColorStateList.valueOf(p.onSurfaceVariant));
            btn.setBackground(rippleOval(p.tileBg, p.ripple));
        }
        btn.setContentDescription(contentDesc);
        btn.setTooltipText(contentDesc);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    public static TextView createSectionHeader(Context ctx, Palette p, String title) {
        TextView tv = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 18);
        lp.bottomMargin = dp(ctx, 8);
        lp.leftMargin = dp(ctx, isMiuix() ? 12 : 6);
        lp.rightMargin = dp(ctx, 6);
        tv.setLayoutParams(lp);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, isMiuix() ? 14f : 13f);
        tv.setTypeface(isMiuix() ? Typeface.DEFAULT : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(p.sectionTitle);
        return tv;
    }

    /** Plain content card (24dp Material 3 / 16dp Miuix). */
    public static LinearLayout createCard(Context ctx, Palette p) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(ctx, 18);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(roundedRect(ctx, p.cardBg, radius(24f, 16f), 0, 0));
        return card;
    }

    /** Adds a "label ........ value" line to an information card and returns the value view. */
    public static TextView addInfoLine(Context ctx, Palette p, LinearLayout card, String label, String value) {
        return addInfoLine(ctx, p, card, label, value, false);
    }

    /** As above; {@code monospace} renders the value in a monospace font (versions, IDs). */
    public static TextView addInfoLine(
            Context ctx, Palette p, LinearLayout card, String label, String value, boolean monospace) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (card.getChildCount() > 0) {
            lp.topMargin = dp(ctx, 10);
        }
        row.setLayoutParams(lp);

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, isMiuix() ? 14f : 13f);
        tvLabel.setTextColor(p.onSurfaceVariant);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.rightMargin = dp(ctx, 16);
        row.addView(tvLabel, llp);

        TextView tvValue = new TextView(ctx);
        tvValue.setText(value);
        tvValue.setGravity(Gravity.END);
        tvValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, isMiuix() ? 14f : 13f);
        tvValue.setTypeface(monospace
                ? Typeface.MONOSPACE
                : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvValue.setTextColor(p.onSurface);
        tvValue.setTextIsSelectable(true);
        row.addView(tvValue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row);
        return tvValue;
    }

    public enum ButtonStyle {
        FILLED,
        TONAL,
        OUTLINED
    }

    public static LinearLayout createActionButton(
            Context ctx, Palette p, ButtonStyle buttonStyle, int iconRes, String text, int heightDp, Runnable onClick) {
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
        if (buttonStyle == ButtonStyle.FILLED) {
            bgColor = p.primary;
            fgColor = p.onPrimary;
        } else if (isMiuix()) {
            // Miuix has no outlined button: tonal and outlined both use the secondary surface.
            bgColor = p.secondaryContainer;
            fgColor = p.onSurface;
        } else if (buttonStyle == ButtonStyle.TONAL) {
            bgColor = p.secondaryContainer;
            fgColor = p.onSecondaryContainer;
        } else {
            bgColor = Color.TRANSPARENT;
            fgColor = p.primary;
            strokePx = Math.max(1, dp(ctx, 1));
            strokeColor = p.outlineVariant;
        }

        float radiusDp = isMiuix() ? 16f : heightDp / 2f;
        int ripple = buttonStyle == ButtonStyle.FILLED ? withAlpha(p.onPrimary, 0x26) : p.ripple;
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
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, isMiuix() ? 14.5f : 13.5f);
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

    private static void animateAlpha(View view, float alpha) {
        view.animate().cancel();
        if (view.isLaidOut()) {
            view.animate().alpha(alpha).setDuration(220).start();
        } else {
            view.setAlpha(alpha);
        }
    }

    public static void setAnimatedVisibility(View view, int visibility) {
        if (view.getVisibility() == visibility) return;
        if (view.getParent() instanceof android.view.ViewGroup
                && ((android.view.ViewGroup) view.getParent()).isLaidOut()) {
            android.transition.TransitionManager.beginDelayedTransition(
                    (android.view.ViewGroup) view.getParent(),
                    new android.transition.AutoTransition().setDuration(220));
        }
        view.setVisibility(visibility);
    }

    public static void setButtonEnabled(View btn, boolean enabled) {
        btn.setEnabled(enabled);
        animateAlpha(btn, enabled ? 1.0f : 0.42f);
    }

    // ---------------------------------------------------------------------------------------------
    // Grouped list rows
    // ---------------------------------------------------------------------------------------------

    /**
     * Shared row shell. Material 3 uses segmented cards (24dp outer / 6dp inner corners with 2dp
     * gaps); Miuix merges a group into one continuous 16dp card with no gaps.
     */
    private static LinearLayout createRowShell(
            Context ctx, Palette p, float topRadiusDp, float bottomRadiusDp, int bottomGapDp) {
        if (isMiuix()) {
            topRadiusDp = topRadiusDp >= 20f ? 16f : 0f;
            bottomRadiusDp = bottomRadiusDp >= 20f ? 16f : 0f;
            bottomGapDp = 0;
        }
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(ctx, bottomGapDp);
        card.setLayoutParams(clp);
        int padH = dp(ctx, 16);
        int padV = dp(ctx, isMiuix() ? 16 : 15);
        card.setPadding(padH, padV, padH, padV);
        card.setBackground(rippleSegmented(ctx, p.cardBg, p.ripple, topRadiusDp, bottomRadiusDp, 0, 0));
        card.setClickable(true);
        card.setFocusable(true);
        return card;
    }

    /** Leading icon of a row: tonal circle (Material 3) or plain glyph (Miuix). */
    private static FrameLayout createRowIcon(Context ctx, int iconRes, ImageView[] outIcon) {
        FrameLayout wrap = new FrameLayout(ctx);
        int boxSize = dp(ctx, isMiuix() ? 28 : 40);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(boxSize, boxSize);
        ilp.rightMargin = dp(ctx, 14);
        wrap.setLayoutParams(ilp);
        ImageView iv = new ImageView(ctx);
        int iconSize = dp(ctx, isMiuix() ? 24 : 21);
        iv.setLayoutParams(new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        iv.setImageResource(iconRes);
        wrap.addView(iv);
        outIcon[0] = iv;
        return wrap;
    }

    private static LinearLayout createRowText(
            Context ctx, Palette p, String title, String subtitle, TextView[] out) {
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = dp(ctx, 12);
        textCol.setLayoutParams(tlp);

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, isMiuix() ? 16.5f : 15.5f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setTextColor(p.onSurface);
        textCol.addView(titleView);

        TextView subtitleView = new TextView(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(ctx, 3);
        subtitleView.setLayoutParams(slp);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, isMiuix() ? 13.5f : 12.5f);
        subtitleView.setLineSpacing(dpf(ctx, 2f), 1.0f);
        subtitleView.setTextColor(p.onSurfaceVariant);
        subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        textCol.addView(subtitleView);

        out[0] = titleView;
        out[1] = subtitleView;
        return textCol;
    }

    /** Settings row with leading icon, title, dynamic subtitle and a trailing switch. */
    public static final class SwitchRow {
        public final LinearLayout card;
        public final FrameLayout iconWrap;
        public final ImageView iconView;
        public final TextView titleView;
        public final TextView subtitleView;
        public final ToggleSwitch toggle;
        private final Palette palette;

        public SwitchRow(
                Context ctx, Palette p, int iconRes, String title, String initialSub,
                float topRadiusDp, float bottomRadiusDp, int bottomGapDp) {
            this.palette = p;
            card = createRowShell(ctx, p, topRadiusDp, bottomRadiusDp, bottomGapDp);
            ImageView[] icon = new ImageView[1];
            iconWrap = createRowIcon(ctx, iconRes, icon);
            iconView = icon[0];
            card.addView(iconWrap);
            TextView[] texts = new TextView[2];
            card.addView(createRowText(ctx, p, title, initialSub, texts));
            titleView = texts[0];
            subtitleView = texts[1];
            toggle = new ToggleSwitch(ctx, p);
            card.addView(toggle);
            card.setOnClickListener(v -> {
                if (toggle.isEnabled()) toggle.performUserToggle();
            });
            refreshIconTint(false);
        }

        public void refreshIconTint(boolean checked) {
            if (isMiuix()) {
                iconWrap.setBackground(null);
                iconView.setImageTintList(ColorStateList.valueOf(palette.onSurface));
                return;
            }
            int wrapBg = checked ? palette.secondaryContainer : palette.tileBg;
            int tint = checked ? palette.onSecondaryContainer : palette.onSurfaceVariant;
            iconWrap.setBackground(oval(wrapBg));
            iconView.setImageTintList(ColorStateList.valueOf(tint));
        }

        public void setSubtitle(String subtitle) {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        }

        public void setRowState(boolean checked, boolean enabled, String subtitle) {
            toggle.setChecked(checked, false);
            toggle.setEnabled(enabled);
            setSubtitle(subtitle);
            animateAlpha(titleView, enabled ? 1.0f : 0.62f);
            animateAlpha(subtitleView, enabled ? 1.0f : 0.58f);
            animateAlpha(iconWrap, enabled ? 1.0f : 0.55f);
            refreshIconTint(checked && enabled);
        }
    }

    /** Navigation / choice row: icon, title, subtitle, trailing value text and chevron. */
    public static final class NavRow {
        public final LinearLayout card;
        public final TextView titleView;
        public final TextView subtitleView;
        public final TextView valueView;
        private final FrameLayout iconWrap;
        private final ImageView iconView;

        public NavRow(
                Context ctx, Palette p, int iconRes, String title, String subtitle,
                float topRadiusDp, float bottomRadiusDp, int bottomGapDp, Runnable onClick) {
            card = createRowShell(ctx, p, topRadiusDp, bottomRadiusDp, bottomGapDp);
            ImageView[] icon = new ImageView[1];
            iconWrap = createRowIcon(ctx, iconRes, icon);
            iconView = icon[0];
            if (isMiuix()) {
                icon[0].setImageTintList(ColorStateList.valueOf(p.onSurface));
            } else {
                iconWrap.setBackground(oval(p.tileBg));
                icon[0].setImageTintList(ColorStateList.valueOf(p.onSurfaceVariant));
            }
            card.addView(iconWrap);
            TextView[] texts = new TextView[2];
            card.addView(createRowText(ctx, p, title, subtitle, texts));
            titleView = texts[0];
            subtitleView = texts[1];

            valueView = new TextView(ctx);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, isMiuix() ? 14f : 13f);
            valueView.setTextColor(isMiuix() ? p.onSurfaceVariant : p.primary);
            valueView.setMaxLines(1);
            valueView.setVisibility(View.GONE);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.rightMargin = dp(ctx, 4);
            card.addView(valueView, vlp);

            ImageView chevron = new ImageView(ctx);
            int cs = dp(ctx, 20);
            chevron.setImageResource(R.drawable.ic_chevron_right_24);
            chevron.setImageTintList(ColorStateList.valueOf(withAlpha(p.onSurfaceVariant, 0xB0)));
            card.addView(chevron, new LinearLayout.LayoutParams(cs, cs));

            card.setOnClickListener(v -> {
                if (card.isEnabled()) onClick.run();
            });
        }

        /** Replaces the icon with a round, untinted image that fills the icon box (an avatar). */
        public void setIconBitmap(Bitmap bitmap) {
            iconView.setImageTintList(null);
            iconView.setImageBitmap(bitmap);
            iconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iconView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iconWrap.setBackground(null);
            iconWrap.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            iconWrap.setClipToOutline(true);
        }

        public void setValue(String value) {
            valueView.setText(value);
            valueView.setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
        }

        public void setSubtitle(String subtitle) {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        }

        public void setEnabled(boolean enabled) {
            card.setEnabled(enabled);
            animateAlpha(card, enabled ? 1f : 0.55f);
        }
    }

    /** Row with icon, title, trailing value label and a full-width slider underneath. */
    public static final class SliderRow {
        public final LinearLayout card;
        public final Slider slider;
        private final TextView valueView;
        private final TextView titleView;
        private final String unit;

        public SliderRow(
                Context ctx, Palette p, int iconRes, String title, int min, int max, int value, String unit,
                float topRadiusDp, float bottomRadiusDp, int bottomGapDp) {
            this.unit = unit;
            LinearLayout shell = createRowShell(ctx, p, topRadiusDp, bottomRadiusDp, bottomGapDp);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setClickable(false);
            shell.setFocusable(false);
            card = shell;

            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            ImageView[] icon = new ImageView[1];
            FrameLayout iconWrap = createRowIcon(ctx, iconRes, icon);
            if (isMiuix()) {
                icon[0].setImageTintList(ColorStateList.valueOf(p.onSurface));
            } else {
                iconWrap.setBackground(oval(p.tileBg));
                icon[0].setImageTintList(ColorStateList.valueOf(p.onSurfaceVariant));
            }
            head.addView(iconWrap);
            TextView[] texts = new TextView[2];
            head.addView(createRowText(ctx, p, title, "", texts));
            titleView = texts[0];
            valueView = new TextView(ctx);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, isMiuix() ? 14f : 13f);
            valueView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            valueView.setTextColor(isMiuix() ? p.onSurfaceVariant : p.primary);
            head.addView(valueView);
            card.addView(head, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            slider = new Slider(ctx, p, min, max, value);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(ctx, 10);
            card.addView(slider, slp);
            showValue(value);
        }

        public void showValue(int value) {
            valueView.setText(value + unit);
        }

        public void setEnabled(boolean enabled) {
            slider.setEnabled(enabled);
            animateAlpha(card, enabled ? 1f : 0.55f);
            titleView.setEnabled(enabled);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Switch
    // ---------------------------------------------------------------------------------------------

    /**
     * Two-style switch: Material 3 pill (52x32dp, outlined track, check / cross thumb glyphs) or
     * Miuix switch (49x28dp, solid track, plain white thumb).
     */
    public static final class ToggleSwitch extends View implements Checkable {
        public interface OnCheckedChangeListener {
            void onCheckedChanged(ToggleSwitch view, boolean isChecked);
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

        public ToggleSwitch(Context ctx, Palette palette) {
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
            setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, widthDp()), dp(ctx, heightDp())));
        }

        private static int widthDp() {
            return isMiuix() ? 49 : 52;
        }

        private static int heightDp() {
            return isMiuix() ? 28 : 32;
        }

        public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
            this.listener = l;
        }

        @Override
        public void setChecked(boolean value) {
            setChecked(value, isAttachedToWindow());
        }

        public void setChecked(boolean value, boolean animate) {
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
                checkAnimator.setInterpolator(STANDARD_EASING);
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

        public void performUserToggle() {
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
            pressAnimator.setInterpolator(STANDARD_EASING);
            pressAnimator.addUpdateListener(a -> {
                pressProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            pressAnimator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = dp(getContext(), widthDp());
            int h = dp(getContext(), heightDp());
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            int alphaLayer = isEnabled() ? 255 : 97;
            if (alphaLayer < 255) {
                canvas.saveLayerAlpha(0, 0, w, h, alphaLayer);
            }
            if (isMiuix()) {
                drawMiuix(canvas, w, h);
            } else {
                drawMaterial(canvas, w, h);
            }
            if (alphaLayer < 255) {
                canvas.restore();
            }
        }

        private void drawMaterial(Canvas canvas, float w, float h) {
            float strokeHalf = borderPaint.getStrokeWidth() / 2f;
            trackPaint.setColor(blend(palette.surfaceHighest, palette.primary, progress));
            trackRect.set(strokeHalf, strokeHalf, w - strokeHalf, h - strokeHalf);
            float trackRadius = trackRect.height() / 2f;
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint);

            int borderAlpha = Math.round((1f - progress) * 255f);
            if (borderAlpha > 4) {
                borderPaint.setColor(withAlpha(palette.outline, borderAlpha));
                canvas.drawRoundRect(trackRect, trackRadius, trackRadius, borderPaint);
            }

            float startCx = h / 2f;
            float endCx = w - (h / 2f);
            float cx = startCx + (endCx - startCx) * progress;
            float cy = h / 2f;
            float thumbRadius = dpf(getContext(), 12f) + dpf(getContext(), 2f) * pressProgress;
            thumbPaint.setColor(blend(palette.outline, palette.onPrimary, progress));
            canvas.drawCircle(cx, cy, thumbRadius, thumbPaint);

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
        }

        private void drawMiuix(Canvas canvas, float w, float h) {
            trackPaint.setColor(blend(palette.surfaceHighest, palette.primary, progress));
            trackRect.set(0f, 0f, w, h);
            float trackRadius = h / 2f;
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint);

            float inset = dpf(getContext(), 4f);
            float baseRadius = (h / 2f) - inset;
            float thumbRadius = baseRadius + dpf(getContext(), 1.5f) * pressProgress;
            float startCx = inset + baseRadius;
            float endCx = w - inset - baseRadius;
            float cx = startCx + (endCx - startCx) * progress;
            int offThumb = palette.isNight ? 0xFFD9D9D9 : 0xFFFFFFFF;
            thumbPaint.setColor(blend(offThumb, 0xFFFFFFFF, progress));
            canvas.drawCircle(cx, h / 2f, thumbRadius, thumbPaint);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Slider
    // ---------------------------------------------------------------------------------------------

    /**
     * Two-style slider: Material 3 Expressive (16dp track, 4dp handle with gaps) or Miuix
     * (28dp rounded track filled with the primary color, no handle).
     */
    public static final class Slider extends View {
        public interface Listener {
            void onValueChanged(int value, boolean fromUser);

            void onStopTracking(int value);
        }

        private final Palette palette;
        private final int min;
        private final int max;
        private int value;
        private Listener listener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public Slider(Context ctx, Palette p, int min, int max, int value) {
            super(ctx);
            this.palette = p;
            this.min = min;
            this.max = Math.max(min + 1, max);
            this.value = Math.max(min, Math.min(this.max, value));
            setFocusable(true);
        }

        public void setListener(Listener l) {
            this.listener = l;
        }

        public int getValue() {
            return value;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int h = dp(getContext(), isMiuix() ? 28 : 40);
            setMeasuredDimension(
                    MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    updateFromX(e.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateFromX(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (listener != null) listener.onStopTracking(value);
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        private void updateFromX(float x) {
            float pad = isMiuix() ? 0f : dpf(getContext(), 2f);
            float frac = (x - pad) / Math.max(1f, getWidth() - 2 * pad);
            int next = min + Math.round(Math.max(0f, Math.min(1f, frac)) * (max - min));
            if (next != value) {
                value = next;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
                if (listener != null) listener.onValueChanged(value, true);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float frac = (value - min) / (float) (max - min);
            if (!isEnabled()) {
                c.saveLayerAlpha(0, 0, w, h, 110);
            }
            if (isMiuix()) {
                float r = h / 2f;
                paint.setColor(palette.surfaceHighest);
                rect.set(0, 0, w, h);
                c.drawRoundRect(rect, r, r, paint);
                paint.setColor(palette.primary);
                float fillW = Math.max(h, w * frac);
                rect.set(0, 0, fillW, h);
                c.drawRoundRect(rect, r, r, paint);
            } else {
                float trackH = dpf(getContext(), 16f);
                float top = (h - trackH) / 2f;
                float r = trackH / 2f;
                float handleW = dpf(getContext(), 4f);
                float gap = dpf(getContext(), 6f);
                float hx = handleW / 2f + (w - handleW) * frac;
                paint.setColor(palette.secondaryContainer);
                rect.set(Math.min(w, hx + gap), top, w, top + trackH);
                if (rect.width() > 0) c.drawRoundRect(rect, r, r, paint);
                paint.setColor(palette.primary);
                rect.set(0, top, Math.max(0, hx - gap), top + trackH);
                if (rect.width() > 0) c.drawRoundRect(rect, r, r, paint);
                rect.set(hx - handleW / 2f, 0, hx + handleW / 2f, h);
                c.drawRoundRect(rect, handleW / 2f, handleW / 2f, paint);
            }
            if (!isEnabled()) {
                c.restore();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Dialogs
    // ---------------------------------------------------------------------------------------------

    /** Creates the dialog frame for the active style and returns the dialog with its content root. */
    private static Dialog createDialog(Activity activity, Palette p, String title, LinearLayout[] outRoot) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean miuix = isMiuix();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        root.setPadding(pad, dp(activity, miuix ? 26 : 24), pad, dp(activity, miuix ? 24 : 18));
        root.setBackground(roundedRect(activity, p.surfaceHigh, miuix ? 32f : 28f, 0, 0));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setGravity(miuix ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setTextColor(p.onSurface);
        root.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int maxW = Math.min(screenW - dp(activity, miuix ? 24 : 36), dp(activity, 420));
            w.setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (miuix) {
                // Miuix dialogs sit at the bottom of the screen on phones.
                w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                WindowManager.LayoutParams attrs = w.getAttributes();
                attrs.y = dp(activity, 12);
                w.setAttributes(attrs);
            }
        }
        outRoot[0] = root;
        return dialog;
    }

    private static ScrollView createDialogScroll(Activity activity) {
        final int maxScrollHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.56f);
        ScrollView scroll = new ScrollView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST));
            }
        };
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(activity, 14);
        slp.bottomMargin = dp(activity, isMiuix() ? 24 : 18);
        scroll.setLayoutParams(slp);
        return scroll;
    }

    /**
     * Message dialog with an optional secondary action. Material 3: 28dp corners, right-aligned
     * buttons. Miuix: bottom-anchored, 32dp corners, centered text, equal-width buttons.
     */
    public static void showDialog(
            Activity activity, Palette p, String title, String message, boolean monospaceBody,
            String neutralText, Runnable onNeutral, String positiveText) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        boolean miuix = isMiuix();
        LinearLayout[] holder = new LinearLayout[1];
        Dialog dialog = createDialog(activity, p, title, holder);
        LinearLayout root = holder[0];

        ScrollView scroll = createDialogScroll(activity);
        TextView tvMsg = new TextView(activity);
        tvMsg.setText(message);
        tvMsg.setTextIsSelectable(true);
        if (monospaceBody) {
            int codePad = dp(activity, 12);
            tvMsg.setPadding(codePad, codePad, codePad, codePad);
            tvMsg.setBackground(roundedRect(activity, miuix ? p.tileBg : p.pageBg, miuix ? 16f : 14f, 0, 0));
            tvMsg.setTypeface(Typeface.MONOSPACE);
            tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            tvMsg.setTextColor(p.onSurface);
        } else {
            if (miuix) tvMsg.setGravity(Gravity.CENTER_HORIZONTAL);
            tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, miuix ? 15f : 14f);
            tvMsg.setLineSpacing(dpf(activity, 3f), 1.0f);
            tvMsg.setTextColor(p.onSurfaceVariant);
        }
        scroll.addView(tvMsg);
        root.addView(scroll);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        int btnHeight = miuix ? 48 : 40;

        if (neutralText != null && onNeutral != null) {
            LinearLayout btnNeutral = createActionButton(
                    activity, p, ButtonStyle.TONAL, 0, neutralText, btnHeight, () -> {
                        onNeutral.run();
                        dialog.dismiss();
                    });
            LinearLayout.LayoutParams nlp = miuix
                    ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.rightMargin = dp(activity, miuix ? 12 : 10);
            btnNeutral.setLayoutParams(nlp);
            actions.addView(btnNeutral);
        }

        String pos = positiveText != null ? positiveText : activity.getString(R.string.ui_ok);
        LinearLayout btnPos = createActionButton(activity, p, ButtonStyle.FILLED, 0, pos, btnHeight, dialog::dismiss);
        btnPos.setLayoutParams(miuix
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(btnPos);
        root.addView(actions);
        dialog.show();
    }

    public interface OnChoice {
        void onChoice(int index);
    }

    /** Single-choice list dialog (SukiSU-style option picker). */
    public static void showChoiceDialog(
            Activity activity, Palette p, String title, String[] options, int selected, OnChoice onChoice) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        boolean miuix = isMiuix();
        LinearLayout[] holder = new LinearLayout[1];
        Dialog dialog = createDialog(activity, p, title, holder);
        LinearLayout root = holder[0];

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = dp(activity, 14);
        listLp.bottomMargin = dp(activity, 16);
        root.addView(list, listLp);

        for (int i = 0; i < options.length; i++) {
            final int index = i;
            boolean isSel = i == selected;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = dp(activity, 16);
            row.setPadding(padH, dp(activity, 14), padH, dp(activity, 14));
            int fill = isSel ? (miuix ? withAlpha(p.primary, 0x1A) : p.secondaryContainer) : Color.TRANSPARENT;
            row.setBackground(rippleSegmented(activity, fill, p.ripple, 16f, 16f, 0, 0));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rlp.topMargin = dp(activity, 4);

            TextView tv = new TextView(activity);
            tv.setText(options[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            tv.setTextColor(isSel ? (miuix ? p.primary : p.onSecondaryContainer) : p.onSurface);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (isSel) {
                ImageView check = new ImageView(activity);
                check.setImageResource(R.drawable.ic_check_24);
                check.setImageTintList(ColorStateList.valueOf(miuix ? p.primary : p.onSecondaryContainer));
                int cs = dp(activity, 20);
                row.addView(check, new LinearLayout.LayoutParams(cs, cs));
            }
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (index != selected) onChoice.onChoice(index);
            });
            list.addView(row, rlp);
        }

        LinearLayout btnCancel = createActionButton(
                activity, p, ButtonStyle.TONAL, 0, activity.getString(R.string.ui_cancel), miuix ? 48 : 40, dialog::dismiss);
        LinearLayout.LayoutParams clp = miuix
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.END;
        root.addView(btnCancel, clp);
        dialog.show();
    }
}
