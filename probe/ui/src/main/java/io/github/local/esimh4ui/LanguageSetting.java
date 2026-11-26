package io.github.local.esimh4ui;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.os.LocaleList;
import java.util.Locale;

/**
 * Per-app language override (Android 13+ {@link LocaleManager}). The system stores the choice,
 * applies it to every activity and lists it under the per-app language settings.
 */
public final class LanguageSetting {
    public static final int FOLLOW_SYSTEM = 0;
    private static final String[] TAGS = {"", "en", "zh-Hans", "zh-Hant", "ja"};

    private LanguageSetting() {}

    /** Option labels; languages are written in their own script so users can always find theirs. */
    public static String[] labels(Context ctx) {
        return new String[]{ctx.getString(R.string.lang_system), "English", "简体中文", "繁體中文", "日本語"};
    }

    public static int current(Context ctx) {
        LocaleManager lm = ctx.getSystemService(LocaleManager.class);
        if (lm == null) return FOLLOW_SYSTEM;
        LocaleList list = lm.getApplicationLocales();
        if (list == null || list.isEmpty()) return FOLLOW_SYSTEM;
        Locale l = list.get(0);
        if ("en".equals(l.getLanguage())) return 1;
        if ("ja".equals(l.getLanguage())) return 4;
        if ("zh".equals(l.getLanguage())) {
            String script = l.getScript();
            String region = l.getCountry();
            boolean hant = "Hant".equals(script)
                    || (script.isEmpty() && ("TW".equals(region) || "HK".equals(region) || "MO".equals(region)));
            return hant ? 3 : 2;
        }
        return FOLLOW_SYSTEM;
    }

    public static String currentLabel(Context ctx) {
        return labels(ctx)[current(ctx)];
    }

    /** Applies the locale, then replaces the UI task through the launcher splash. */
    public static void apply(Context ctx, int index) {
        if (index < 0 || index >= TAGS.length || index == current(ctx)) return;
        LocaleManager lm = ctx.getSystemService(LocaleManager.class);
        if (lm == null) return;
        lm.setApplicationLocales(index == FOLLOW_SYSTEM
                ? LocaleList.getEmptyLocaleList()
                : LocaleList.forLanguageTags(TAGS[index]));
        AppRestart.restart(ctx);
    }

    public static void showPicker(Activity activity, UiKit.Palette p) {
        UiKit.showChoiceDialog(activity, p, activity.getString(R.string.lang_title),
                labels(activity), current(activity), index -> apply(activity, index));
    }
}
