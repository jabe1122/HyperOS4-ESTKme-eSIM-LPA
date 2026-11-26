package io.github.local.esimh4probe;

import android.os.Bundle;
import android.widget.LinearLayout;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;

/**
 * About page, opened from the settings tab. The Miuix style shows the Compose page with the
 * HyperOS background effect ({@code AboutScreen.kt}); the Material 3 style shows a View page
 * ({@link AboutSection}).
 */
public final class AboutActivity extends ThemedActivity {
    private Scaffold scaffold;
    private final AboutLogExport logExport = new AboutLogExport(this);

    void onHeaderTap() {
        logExport.onHeaderTap();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UiKit.isMiuix()) {
            UiKit.applyEdgeToEdge(this, palette);
            AboutScreen.setAboutContent(this, palette.isNight);
            return;
        }
        String title = getString(R.string.section_about);
        scaffold = new Scaffold(this, palette, title, null, true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        AboutSection.build(this, palette, col, msg -> scaffold.showToast(msg));
        scaffold.addPage(col, io.github.local.esimh4ui.R.id.ui_page_1, title);
        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);
    }

    @Override
    protected void onDestroy() {
        if (scaffold != null) scaffold.release();
        super.onDestroy();
    }
}
