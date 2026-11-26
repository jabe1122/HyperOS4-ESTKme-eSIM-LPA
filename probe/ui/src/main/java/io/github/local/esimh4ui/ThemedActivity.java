package io.github.local.esimh4ui;

import android.content.Intent;
import android.os.Bundle;
import androidx.activity.ComponentActivity;

/**
 * Base activity that loads the theme settings before the views are built and rebuilds itself
 * when the settings changed while it was in the background (for example in {@link ThemeActivity}).
 *
 * <p>It extends {@link ComponentActivity} so that pages can be written in Compose.
 */
public abstract class ThemedActivity extends ComponentActivity {
    protected UiKit.Palette palette;
    private int themeVersion;
    private boolean themeRecreating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeConfig.load(this);
        super.onCreate(savedInstanceState);
        themeVersion = ThemeConfig.version();
        palette = UiKit.Palette.resolve(this);
        PredictiveActivityBack.install(this);
    }

    /** Returns true when the activity is being recreated because the theme changed. */
    protected boolean recreateIfThemeChanged() {
        if (themeVersion != ThemeConfig.version()) {
            themeRecreating = true;
            recreate();
            return true;
        }
        return false;
    }

    /** True after {@link #recreateIfThemeChanged()} scheduled a rebuild; skip further work then. */
    protected boolean isThemeRecreating() {
        return themeRecreating;
    }

    /**
     * False for pages that follow theme changes on their own (Compose pages read the settings as
     * state), so returning to them does not rebuild them.
     */
    protected boolean recreatesOnThemeChange() {
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (recreatesOnThemeChange()) recreateIfThemeChanged();
    }

    protected void openThemeSettings() {
        openThemeSettings(true);
    }

    /** Opens the theme page; {@code showLanguage} is false when the caller has its own language row. */
    protected void openThemeSettings(boolean showLanguage) {
        startActivity(new Intent(this, ThemeActivity.class)
                .putExtra(ThemeActivity.EXTRA_SHOW_LANGUAGE, showLanguage));
    }
}
