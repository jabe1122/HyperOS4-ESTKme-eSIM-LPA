package io.github.local.esimh4ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Theme settings page (SukiSU-style): UI style, dark mode, dynamic color, Miuix bottom bar
 * (glass / floating) and the custom app background with blur, dim and card opacity.
 *
 * <p>The Miuix style shows the Compose page in {@code ThemeScreen.kt}; the Material 3 style still
 * uses the View page below.
 */
public final class ThemeActivity extends ThemedActivity {
    public static final String EXTRA_SHOW_LANGUAGE = "show_language";
    private static final int REQ_PICK_IMAGE = 4101;

    private Scaffold scaffold;
    private UiKit.NavRow rowNight;
    private boolean composePage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UiKit.isMiuix()) {
            composePage = true;
            UiKit.applyEdgeToEdge(this, palette);
            ThemeScreen.setThemeContent(this, getIntent().getBooleanExtra(EXTRA_SHOW_LANGUAGE, true));
            return;
        }
        scaffold = new Scaffold(this, palette, getString(R.string.personalization_title), null, true);
        scaffold.addPage(buildContent(), R.id.ui_page_1, getString(R.string.personalization_title));
        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);
    }

    @Override
    protected boolean recreatesOnThemeChange() {
        // The Compose page reads the settings as state; rebuilding it on return from the image
        // picker would also drop an import that is still running.
        return !composePage;
    }

    @Override
    protected void onDestroy() {
        if (scaffold != null) scaffold.release();
        super.onDestroy();
    }

    private void addChoice(LinearLayout col, int title, String[] choices, int selected,
                           java.util.function.IntConsumer onSelected) {
        int icon = title == R.string.theme_miuix_version
                ? R.drawable.ic_layers_24
                : title == R.string.theme_predictive_back || title == R.string.theme_back_direction
                        ? R.drawable.ic_arrow_back_24 : R.drawable.ic_style_24;
        UiKit.NavRow row = new UiKit.NavRow(this, palette, icon,
                getString(title), null, 6f, 6f, 2, null);
        row.setValue(choices[selected]);
        row.card.setOnClickListener(v -> UiKit.showChoiceDialog(this, palette, getString(title),
                choices, selected, index -> {
                    onSelected.accept(index);
                    recreateSoon();
                }));
        col.addView(row.card);
    }

    private LinearLayout buildContent() {
        boolean miuix = UiKit.isMiuix();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        android.widget.TextView appearanceTitle = UiKit.createSectionHeader(
                this, palette, getString(R.string.theme_section_appearance));
        col.addView(appearanceTitle);

        String[] styles = {getString(R.string.theme_style_material3), getString(R.string.theme_style_miuix)};
        UiKit.NavRow rowStyle = new UiKit.NavRow(
                this, palette, R.drawable.ic_style_24,
                getString(R.string.theme_style), getString(R.string.theme_style_summary),
                6f, 6f, 2, null);
        rowStyle.setValue(styles[miuix ? 1 : 0]);
        rowStyle.card.setOnClickListener(v -> UiKit.showChoiceDialog(
                this, palette, getString(R.string.theme_style), styles, miuix ? 1 : 0, index -> {
                    ThemeConfig.setStyle(this, index == 1 ? UiKit.Style.MIUIX : UiKit.Style.MATERIAL3);
                }));
        col.addView(rowStyle.card);
        String[] nightModes = {
                getString(R.string.theme_night_system),
                getString(R.string.theme_night_light),
                getString(R.string.theme_night_dark)};
        rowNight = new UiKit.NavRow(
                this, palette, R.drawable.ic_dark_mode_24,
                getString(R.string.theme_night), null, 6f, 6f, 2, null);
        rowNight.setValue(nightModes[ThemeConfig.nightMode]);
        rowNight.card.setOnClickListener(v -> UiKit.showChoiceDialog(
                this, palette, getString(R.string.theme_night), nightModes, ThemeConfig.nightMode, index -> {
                    // The platform recreates the activity if the effective night mode changes.
                    ThemeConfig.setNightMode(this, index);
                    rowNight.setValue(nightModes[index]);
                }));
        col.addView(rowNight.card);

        if (getIntent().getBooleanExtra(EXTRA_SHOW_LANGUAGE, true)) {
            UiKit.NavRow rowLang = new UiKit.NavRow(
                    this, palette, R.drawable.ic_language_24, getString(R.string.lang_title), null,
                    6f, 6f, 2, () -> LanguageSetting.showPicker(this, palette));
            rowLang.setValue(LanguageSetting.currentLabel(this));
            col.addView(rowLang.card);
        }

        UiKit.SwitchRow rowDynamic = new UiKit.SwitchRow(
                this, palette, R.drawable.ic_dynamic_color_24,
                getString(R.string.theme_dynamic_color), "", 6f, 24f, 0);
        rowDynamic.setRowState(ThemeConfig.dynamicColor, true, getString(miuix
                ? R.string.theme_dynamic_color_summary_miuix
                : R.string.theme_dynamic_color_summary_material3));
        rowDynamic.toggle.setOnCheckedChangeListener((sw, checked) -> {
            ThemeConfig.setDynamicColor(this, checked);
            recreateSoon();
        });
        col.addView(rowDynamic.card);

        addChoice(col, R.string.theme_miuix_version, new String[]{"OS2", "OS3", "OS4"},
                ThemeConfig.miuixVersion - 2, index -> ThemeConfig.setMiuixVersion(this, index + 2));
        UiKit.SwitchRow rowAmoled = new UiKit.SwitchRow(this, palette, R.drawable.ic_dark_mode_24,
                getString(R.string.theme_amoled), "", 6f, 6f, 2);
        rowAmoled.setRowState(ThemeConfig.amoledBackground, true, getString(R.string.theme_amoled_summary));
        rowAmoled.toggle.setOnCheckedChangeListener((sw, checked) -> {
            ThemeConfig.setAmoledBackground(this, checked);
            recreateSoon();
        });
        col.addView(rowAmoled.card);

        // Top and bottom bars (Miuix only), as in SukiSU-Nova:
        // frosted glass + progressive blur for both bars, liquid glass for the floating bar.
        if (ThemeConfig.miuixVersion > 2) {
        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.theme_section_bottom_bar)));
        String miuixOnly = getString(R.string.theme_miuix_only);

        UiKit.SwitchRow rowGlass = new UiKit.SwitchRow(
                this, palette, R.drawable.ic_bottom_bar_24,
                getString(R.string.theme_bar_glass), "", 24f, 6f, 2);
        rowGlass.setRowState(ThemeConfig.barGlass, miuix && ThemeConfig.miuixVersion == 4,
                miuix ? getString(R.string.theme_bar_glass_summary) : miuixOnly);
        rowGlass.card.setVisibility(ThemeConfig.miuixVersion == 4 ? View.VISIBLE : View.GONE);
        col.addView(rowGlass.card);

        UiKit.SwitchRow rowProgressive = new UiKit.SwitchRow(
                this, palette, R.drawable.ic_blur_24,
                getString(R.string.theme_progressive_blur), "", 6f, 6f, 2);
        rowProgressive.setRowState(ThemeConfig.progressiveBlur, true,
                getString(R.string.theme_progressive_blur_summary));
        rowProgressive.card.setVisibility(miuix && ThemeConfig.miuixVersion == 4 && ThemeConfig.barGlass
                ? View.VISIBLE : View.GONE);
        rowProgressive.toggle.setOnCheckedChangeListener((sw, checked) -> ThemeConfig.setProgressiveBlur(this, checked));
        col.addView(rowProgressive.card);

        rowGlass.toggle.setOnCheckedChangeListener((sw, checked) -> {
            ThemeConfig.setBarGlass(this, checked);
            android.transition.TransitionManager.beginDelayedTransition(col,
                    new android.transition.AutoTransition().setDuration(250));
            rowProgressive.card.setVisibility(miuix && ThemeConfig.miuixVersion == 4 && checked
                    ? View.VISIBLE : View.GONE);
        });

        UiKit.SwitchRow rowFloating = new UiKit.SwitchRow(
                this, palette, R.drawable.ic_floating_bar_24,
                getString(R.string.theme_bar_floating), "", 6f, 6f, 2);
        rowFloating.setRowState(ThemeConfig.barFloating, miuix,
                miuix ? getString(R.string.theme_bar_floating_summary) : miuixOnly);
        col.addView(rowFloating.card);

        UiKit.SwitchRow rowLiquid = new UiKit.SwitchRow(
                this, palette, R.drawable.ic_liquid_glass_24,
                getString(R.string.theme_liquid_glass), "", 6f, 24f, 0);
        rowLiquid.setRowState(ThemeConfig.liquidGlass, miuix,
                getString(R.string.theme_liquid_glass_summary));
        rowLiquid.card.setVisibility(ThemeConfig.miuixVersion == 4 && ThemeConfig.barFloating
                ? View.VISIBLE : View.GONE);
        rowLiquid.toggle.setOnCheckedChangeListener((sw, checked) -> ThemeConfig.setLiquidGlass(this, checked));
        if (ThemeConfig.miuixVersion == 4) col.addView(rowLiquid.card);

        rowFloating.toggle.setOnCheckedChangeListener((sw, checked) -> {
            ThemeConfig.setBarFloating(this, checked);
            android.transition.TransitionManager.beginDelayedTransition(col,
                    new android.transition.AutoTransition().setDuration(250));
            rowLiquid.card.setVisibility(ThemeConfig.miuixVersion == 4 && checked
                    ? View.VISIBLE : View.GONE);
        });

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.personalization_navigation)));
        java.util.List<Integer> backStyles = java.util.Arrays.asList(ThemeConfig.BACK_NONE,
                ThemeConfig.BACK_AOSP, ThemeConfig.BACK_MIUIX, ThemeConfig.BACK_CLASSIC, ThemeConfig.BACK_SCALE);
        addChoice(col, R.string.theme_predictive_back, new String[]{getString(R.string.theme_back_none),
                getString(R.string.theme_back_aosp), getString(R.string.theme_back_miuix),
                getString(R.string.theme_back_classic), getString(R.string.theme_back_scale)},
                backStyles.indexOf(ThemeConfig.predictiveBack),
                index -> ThemeConfig.setPredictiveBack(this, backStyles.get(index)));
        if (ThemeConfig.predictiveBack == ThemeConfig.BACK_SCALE) {
            addChoice(col, R.string.theme_back_direction, new String[]{getString(R.string.theme_back_follow),
                    getString(R.string.theme_back_right), getString(R.string.theme_back_left)},
                    ThemeConfig.backExitDirection, index -> ThemeConfig.setBackExitDirection(this, index));
        }

        }

        // Background
        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.theme_section_background)));
        boolean hasBg = palette.hasBackground;
        if (ThemeConfig.showTexture) {
            UiKit.SwitchRow rowTexture = new UiKit.SwitchRow(this, palette, R.drawable.ic_style_24,
                    getString(R.string.theme_show_texture), "", 24f, 6f, 2);
            rowTexture.setRowState(true, true, "");
            rowTexture.toggle.setOnCheckedChangeListener((sw, checked) -> {
                ThemeConfig.setShowTexture(this, checked);
                recreateSoon();
            });
            col.addView(rowTexture.card);
        }

        UiKit.NavRow rowImage = new UiKit.NavRow(
                this, palette, R.drawable.ic_image_24,
                getString(R.string.theme_bg_image), getString(R.string.theme_bg_image_summary),
                ThemeConfig.showTexture ? 6f : 24f, 6f, 2, null);
        rowImage.setValue(getString(hasBg ? R.string.theme_bg_image_set : R.string.theme_bg_image_unset));
        rowImage.card.setOnClickListener(v -> {
            if (!hasBg) {
                pickImage();
                return;
            }
            String[] actions = {getString(R.string.theme_bg_image_replace), getString(R.string.theme_bg_image_remove)};
            UiKit.showChoiceDialog(this, palette, getString(R.string.theme_bg_image), actions, -1, index -> {
                if (index == 0) {
                    pickImage();
                } else {
                    ThemeConfig.clearBackground(this);
                    recreate();
                }
            });
        });
        col.addView(rowImage.card);

        UiKit.SliderRow rowBlur = new UiKit.SliderRow(
                this, palette, R.drawable.ic_blur_24, getString(R.string.theme_bg_blur),
                0, 25, ThemeConfig.bgBlur, " dp", 6f, 6f, 2);
        rowBlur.card.setVisibility(hasBg ? View.VISIBLE : View.GONE);
        rowBlur.slider.setListener(new UiKit.Slider.Listener() {
            @Override
            public void onValueChanged(int value, boolean fromUser) {
                rowBlur.showValue(value);
                if (scaffold.background != null) scaffold.background.setBlur(value);
            }

            @Override
            public void onStopTracking(int value) {
                ThemeConfig.setBgBlur(ThemeActivity.this, value);
            }
        });
        col.addView(rowBlur.card);

        UiKit.SliderRow rowDim = new UiKit.SliderRow(
                this, palette, R.drawable.ic_brightness_24, getString(R.string.theme_bg_dim),
                0, 80, ThemeConfig.bgDim, "%", 6f, 6f, 2);
        rowDim.card.setVisibility(hasBg ? View.VISIBLE : View.GONE);
        rowDim.slider.setListener(new UiKit.Slider.Listener() {
            @Override
            public void onValueChanged(int value, boolean fromUser) {
                rowDim.showValue(value);
                if (scaffold.background != null) scaffold.background.setDim(value);
            }

            @Override
            public void onStopTracking(int value) {
                ThemeConfig.setBgDim(ThemeActivity.this, value);
            }
        });
        col.addView(rowDim.card);

        UiKit.SliderRow rowCard = new UiKit.SliderRow(
                this, palette, R.drawable.ic_opacity_24, getString(R.string.theme_card_alpha),
                40, 100, ThemeConfig.cardAlpha, "%", 6f, 24f, 0);
        rowCard.card.setVisibility(hasBg ? View.VISIBLE : View.GONE);
        rowCard.slider.setListener(new UiKit.Slider.Listener() {
            @Override
            public void onValueChanged(int value, boolean fromUser) {
                rowCard.showValue(value);
            }

            @Override
            public void onStopTracking(int value) {
                if (value != ThemeConfig.cardAlpha) {
                    ThemeConfig.setCardAlpha(ThemeActivity.this, value);
                    recreate();
                }
            }
        });
        col.addView(rowCard.card);

        return col;
    }

    /** Lets the switch animation finish before the page is rebuilt with the new colors. */
    private void recreateSoon() {
        scaffold.root.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) recreate();
        }, 220L);
    }

    @SuppressWarnings("deprecation")
    private void pickImage() {
        try {
            // System photo picker (Android 13+), no storage permission needed.
            Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Throwable e) {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*");
                startActivityForResult(intent, REQ_PICK_IMAGE);
            } catch (Throwable e2) {
                scaffold.showToast(getString(R.string.theme_bg_pick_failed));
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (composePage) return; // the Compose page gets its result through its own launcher
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        scaffold.setBusy(true);
        new Thread(() -> {
            boolean ok = ThemeConfig.importBackground(getApplicationContext(), uri);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                scaffold.setBusy(false);
                if (ok) {
                    recreate();
                } else {
                    scaffold.showToast(getString(R.string.theme_bg_pick_failed));
                }
            });
        }, "ThemeBgImport").start();
    }
}
