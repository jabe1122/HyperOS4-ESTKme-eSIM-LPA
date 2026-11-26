package io.github.local.esimh4ui;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Persistent appearance settings (UI style, dark mode, dynamic color, background, bottom bar),
 * modelled after the theme page of SukiSU managers.
 */
public final class ThemeConfig {
    public static final int NIGHT_FOLLOW_SYSTEM = 0;
    public static final int NIGHT_LIGHT = 1;
    public static final int NIGHT_DARK = 2;

    private static final String PREFS_NAME = "ui_prefs";
    private static final String KEY_STYLE = "ui_style";
    private static final String KEY_NIGHT = "night_mode";
    private static final String KEY_DYNAMIC_PREFIX = "dynamic_color_";
    private static final String KEY_BG_BLUR = "bg_blur";
    private static final String KEY_BG_DIM = "bg_dim";
    private static final String KEY_CARD_ALPHA = "card_alpha";
    private static final String KEY_BAR_GLASS = "bar_glass";
    private static final String KEY_BAR_FLOATING = "bar_floating";
    private static final String KEY_LIQUID_GLASS = "liquid_glass";
    private static final String KEY_PROGRESSIVE = "progressive_blur";
    private static final String BG_FILE = "app_background.jpg";
    public static int miuixVersion = 4;
    public static boolean amoledBackground = false;
    public static boolean showTexture = false;
    // Keep persisted IDs stable when adding animation styles.
    public static final int BACK_AOSP = 0;
    public static final int BACK_SCALE = 1;
    public static final int BACK_CLASSIC = 2;
    public static final int BACK_NONE = 3;
    public static final int BACK_MIUIX = 4;
    /** AOSP, Scale, Classic, None, Miuix; shared by both UI styles. */
    public static int predictiveBack = 0;
    /** 0: follow gesture, 1: right, 2: left. */
    public static int backExitDirection = 0;

    public static UiKit.Style style = UiKit.Style.MIUIX;
    public static int nightMode = NIGHT_FOLLOW_SYSTEM;
    /** Dynamic (Monet) color; stored per style: on by default for Material 3, off for Miuix. */
    public static boolean dynamicColor = true;
    /** Background blur radius in dp (0..25). */
    public static int bgBlur = 0;
    /** Background dim in percent (0..80). */
    public static int bgDim = 20;
    /** Card opacity in percent (40..100); only applied over a custom background. */
    public static int cardAlpha = 85;
    public static boolean barGlass = true;
    public static boolean barFloating = false;
    /** Liquid glass for the floating bottom bar: light blur, edge refraction and a light rim. */
    public static boolean liquidGlass = false;
    /** Progressive blur: the bar blur fades from the screen edge toward the content. */
    public static boolean progressiveBlur = true;

    /** Bumped on every change so other activities can tell they must rebuild. */
    private static int version = 0;
    private static boolean loaded = false;

    private ThemeConfig() {}

    public static int version() {
        return version;
    }

    public static void load(Context ctx) {
        if (loaded) return;
        SharedPreferences sp = prefs(ctx);
        style = UiKit.Style.MATERIAL3.name().equals(sp.getString(KEY_STYLE, null))
                ? UiKit.Style.MATERIAL3
                : UiKit.Style.MIUIX;
        nightMode = sp.getInt(KEY_NIGHT, NIGHT_FOLLOW_SYSTEM);
        dynamicColor = sp.getBoolean(KEY_DYNAMIC_PREFIX + style.name(), style == UiKit.Style.MATERIAL3);
        bgBlur = sp.getInt(KEY_BG_BLUR, 0);
        bgDim = sp.getInt(KEY_BG_DIM, 20);
        cardAlpha = sp.getInt(KEY_CARD_ALPHA, 85);
        miuixVersion = Math.max(2, Math.min(4, sp.getInt("miuix_version", 4)));
        loadVersionPreferences(sp);
        amoledBackground = sp.getBoolean("amoled_background", false);
        showTexture = sp.getBoolean("show_texture", false);
        backExitDirection = Math.max(0, Math.min(2, sp.getInt("back_exit_direction", 0)));
        loaded = true;
    }

    public static void setMiuixVersion(Context ctx, int value) {
        miuixVersion = Math.max(2, Math.min(4, value));
        prefs(ctx).edit().putInt("miuix_version", miuixVersion).apply();
        loadVersionPreferences(prefs(ctx));
        version++;
    }

    private static void loadVersionPreferences(SharedPreferences sp) {
        // Preserve user preferences while enforcing the selected UI version's capabilities.
        barFloating = miuixVersion > 2 && sp.getBoolean(KEY_BAR_FLOATING, false);
        predictiveBack = miuixVersion == 2 ? BACK_NONE
                : Math.max(BACK_AOSP, Math.min(BACK_MIUIX, sp.getInt("predictive_back", BACK_AOSP)));
        boolean supported = miuixVersion == 4;
        barGlass = supported && sp.getBoolean(KEY_BAR_GLASS, true);
        liquidGlass = supported && sp.getBoolean(KEY_LIQUID_GLASS, false);
        progressiveBlur = supported && sp.getBoolean(KEY_PROGRESSIVE, true);
    }

    public static boolean useGlass() {
        return miuixVersion == 4 && barGlass;
    }

    public static boolean useLiquidGlass() {
        return miuixVersion >= 4 && liquidGlass;
    }

    public static void setShowTexture(Context ctx, boolean value) {
        showTexture = value;
        prefs(ctx).edit().putBoolean("show_texture", value).apply();
        version++;
    }

    public static void setAmoledBackground(Context ctx, boolean value) {
        amoledBackground = value;
        prefs(ctx).edit().putBoolean("amoled_background", value).apply();
        version++;
    }

    public static void setPredictiveBack(Context ctx, int value) {
        int preference = Math.max(BACK_AOSP, Math.min(BACK_MIUIX, value));
        predictiveBack = miuixVersion == 2 ? BACK_NONE : preference;
        prefs(ctx).edit().putInt("predictive_back", preference).apply();
        version++;
    }

    public static void setBackExitDirection(Context ctx, int value) {
        backExitDirection = Math.max(0, Math.min(2, value));
        prefs(ctx).edit().putInt("back_exit_direction", backExitDirection).apply();
        version++;
    }

    public static void setStyle(Context ctx, UiKit.Style value) {
        if (style == value) return;
        style = value;
        dynamicColor = prefs(ctx).getBoolean(KEY_DYNAMIC_PREFIX + value.name(), value == UiKit.Style.MATERIAL3);
        prefs(ctx).edit().putString(KEY_STYLE, value.name()).apply();
        version++;
        AppRestart.restart(ctx);
    }

    public static void setNightMode(Context ctx, int mode) {
        nightMode = mode;
        prefs(ctx).edit().putInt(KEY_NIGHT, mode).apply();
        version++;
        applyNightMode(ctx);
    }

    /** Applies the per-app night mode through the platform (Android 12+); the activity recreates itself. */
    public static void applyNightMode(Context ctx) {
        UiModeManager umm = ctx.getSystemService(UiModeManager.class);
        if (umm == null) return;
        int target = nightMode == NIGHT_LIGHT
                ? UiModeManager.MODE_NIGHT_NO
                : nightMode == NIGHT_DARK ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_AUTO;
        try {
            umm.setApplicationNightMode(target);
        } catch (Throwable ignored) {
        }
    }

    public static void setDynamicColor(Context ctx, boolean value) {
        dynamicColor = value;
        prefs(ctx).edit().putBoolean(KEY_DYNAMIC_PREFIX + style.name(), value).apply();
        version++;
    }

    public static void setBgBlur(Context ctx, int value) {
        bgBlur = value;
        prefs(ctx).edit().putInt(KEY_BG_BLUR, value).apply();
        version++;
    }

    public static void setBgDim(Context ctx, int value) {
        bgDim = value;
        prefs(ctx).edit().putInt(KEY_BG_DIM, value).apply();
        version++;
    }

    public static void setCardAlpha(Context ctx, int value) {
        cardAlpha = value;
        prefs(ctx).edit().putInt(KEY_CARD_ALPHA, value).apply();
        version++;
    }

    public static void setBarGlass(Context ctx, boolean value) {
        barGlass = miuixVersion == 4 && value;
        prefs(ctx).edit().putBoolean(KEY_BAR_GLASS, value).apply();
        version++;
    }

    public static void setBarFloating(Context ctx, boolean value) {
        barFloating = miuixVersion > 2 && value;
        prefs(ctx).edit().putBoolean(KEY_BAR_FLOATING, value).apply();
        version++;
    }

    public static void setLiquidGlass(Context ctx, boolean value) {
        liquidGlass = miuixVersion == 4 && value;
        prefs(ctx).edit().putBoolean(KEY_LIQUID_GLASS, value).apply();
        version++;
    }

    public static void setProgressiveBlur(Context ctx, boolean value) {
        progressiveBlur = miuixVersion == 4 && value;
        prefs(ctx).edit().putBoolean(KEY_PROGRESSIVE, value).apply();
        version++;
    }

    public static File backgroundFile(Context ctx) {
        return new File(ctx.getFilesDir(), BG_FILE);
    }

    public static boolean hasBackground(Context ctx) {
        return backgroundFile(ctx).isFile();
    }

    /** Decodes the stored background as a software bitmap (the glass bar redraws it on a software canvas). */
    public static Bitmap loadBackground(Context ctx) {
        File f = backgroundFile(ctx);
        if (!f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable e) {
            return null;
        }
    }

    /** Copies the picked image into app storage, downscaled so its longest side is at most 2160 px. */
    public static boolean importBackground(Context ctx, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2160) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return false;
            int longest = Math.max(bmp.getWidth(), bmp.getHeight());
            if (longest > 2160) {
                float k = 2160f / longest;
                Bitmap scaled = Bitmap.createScaledBitmap(
                        bmp, Math.round(bmp.getWidth() * k), Math.round(bmp.getHeight() * k), true);
                bmp.recycle();
                bmp = scaled;
            }
            File tmp = new File(ctx.getFilesDir(), BG_FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            bmp.recycle();
            boolean ok = tmp.renameTo(backgroundFile(ctx));
            version++;
            return ok;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void clearBackground(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        backgroundFile(ctx).delete();
        version++;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
