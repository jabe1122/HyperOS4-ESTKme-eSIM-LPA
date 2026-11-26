package io.github.local.esimh4probe;

import android.os.Bundle;
import android.widget.LinearLayout;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;

/**
 * Open source licenses page, opened from the About page: the license of this project and of each
 * library in the app, one card per library ("1.13.0, Apache-2.0"). Tapping a library opens its web
 * page; tapping this project shows its license text. The Miuix style shows the Compose page
 * ({@code LicensesScreen.kt}); the Material 3 style shows the View page below.
 */
public final class LicensesActivity extends ThemedActivity {
    private Scaffold scaffold;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UiKit.isMiuix()) {
            UiKit.applyEdgeToEdge(this, palette);
            LicensesScreen.setLicensesContent(this);
            return;
        }
        String title = getString(R.string.licenses_title);
        scaffold = new Scaffold(this, palette, title, null, true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scaffold.addPage(col, io.github.local.esimh4ui.R.id.ui_page_1, title);
        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);

        Thread loader = new Thread(() -> {
            LicenseData data = LicenseData.load(this);
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) bind(col, data);
            });
        }, "EsimH4-Licenses");
        loader.setDaemon(true);
        loader.start();
    }

    @Override
    protected void onDestroy() {
        if (scaffold != null) scaffold.release();
        super.onDestroy();
    }

    private void bind(LinearLayout col, LicenseData data) {
        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.licenses_section_project)));
        col.addView(card(data.project, () -> showProjectLicense(data.project)).card);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.licenses_section_libraries)));
        for (LicenseData.Entry lib : data.libraries) {
            UiKit.NavRow row = card(lib, () -> AboutSection.openUrl(this, lib.url, msg -> scaffold.showToast(msg)));
            if (lib.url == null) row.setEnabled(false);
            col.addView(row.card);
        }
    }

    /** One separate card per entry, 10dp apart. */
    private UiKit.NavRow card(LicenseData.Entry entry, Runnable onClick) {
        return new UiKit.NavRow(this, palette, R.drawable.ic_code_24, entry.title, entry.summary,
                24f, 24f, 10, onClick);
    }

    private void showProjectLicense(LicenseData.Entry entry) {
        String text = entry.text != null ? entry.text : getString(R.string.licenses_no_text, entry.licenses);
        UiKit.showDialog(this, palette, entry.title, text, false,
                entry.url != null ? getString(R.string.licenses_open_web) : null,
                entry.url != null ? () -> AboutSection.openUrl(this, entry.url, msg -> scaffold.showToast(msg)) : null,
                getString(R.string.diag_close));
    }
}
