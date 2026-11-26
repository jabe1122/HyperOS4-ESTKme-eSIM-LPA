package io.github.local.esimh4probe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.local.esimh4ui.RefreshScrollView;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Device and eUICC status, opened from the settings tab (below the bridge master switch).
 * Pull down to read the live values again. The Miuix style shows the Compose page
 * ({@code DeviceStatusScreen.kt}); the Material 3 style shows the View page below. Both show
 * {@link #read}.
 */
public final class DeviceStatusActivity extends ThemedActivity {
    private static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    private static final String KEY_ENABLE_ESIM = "is_enable_esim_for_user";
    /** Probe target for the "LPA:// links" line; any activation code works. */
    private static final String SAMPLE_LPA_CODE = "LPA:1$test.esim.local$MOCK-TEST-001";

    /** One label / value line. */
    static final class Line {
        final String label;
        final String value;
        /** Null for plain values; otherwise green (true) or red (false). */
        final Boolean ok;
        /** Monospace value (versions, IDs). */
        final boolean mono;

        Line(String label, String value, Boolean ok, boolean mono) {
            this.label = label;
            this.value = value;
            this.ok = ok;
            this.mono = mono;
        }
    }

    /** A titled card of lines. */
    static final class Section {
        final String title;
        final List<Line> lines;

        Section(String title, List<Line> lines) {
            this.title = title;
            this.lines = lines;
        }
    }

    private Scaffold scaffold;
    /** View page: the value views, in the order of {@link #read}. */
    private final List<TextView> valueViews = new ArrayList<>();
    /** Compose page: re-reads the values; set by {@code DeviceStatusScreen.kt}. */
    Runnable composeRefresh;

    /** The theme colors, for the Compose page (the field is protected). */
    UiKit.Palette paletteColors() {
        return palette;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (UiKit.isMiuix()) {
            UiKit.applyEdgeToEdge(this, palette);
            DeviceStatusScreen.setDeviceStatusContent(this);
            return;
        }
        String title = getString(R.string.row_device_status);
        scaffold = new Scaffold(this, palette, title, null, true);
        RefreshScrollView page = scaffold.addPage(buildContent(), io.github.local.esimh4ui.R.id.ui_page_1, title);
        page.setOnRefreshListener(() -> {
            refresh();
            // The values are read at once; keep the ring visible briefly so the pull registers.
            page.postDelayed(() -> page.setRefreshing(false), 400);
        });
        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);
        // onResume reads the values.
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isThemeRecreating()) return;
        if (composeRefresh != null) {
            composeRefresh.run();
        } else {
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        if (scaffold != null) scaffold.release();
        super.onDestroy();
    }

    private LinearLayout buildContent() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        for (Section section : read(this)) {
            col.addView(UiKit.createSectionHeader(this, palette, section.title));
            LinearLayout card = UiKit.createCard(this, palette);
            for (Line line : section.lines) {
                valueViews.add(UiKit.addInfoLine(this, palette, card, line.label, "…", line.mono));
            }
            col.addView(card);
        }
        return col;
    }

    private void refresh() {
        if (scaffold == null) return;
        int i = 0;
        for (Section section : read(this)) {
            for (Line line : section.lines) {
                if (i >= valueViews.size()) return;
                TextView tv = valueViews.get(i++);
                tv.setText(line.value);
                if (line.ok != null) {
                    tv.setTextColor(line.ok ? (UiKit.isMiuix() ? palette.success : palette.onSurface) : palette.error);
                }
            }
        }
    }

    /** Reads the device lines and the live eUICC status. Fast enough for the UI thread. */
    static List<Section> read(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        String lpaVer;
        try {
            lpaVer = pm.getPackageInfo(ProbeInspector.LPA_PKG, 0).versionName;
        } catch (Throwable e) {
            lpaVer = ctx.getString(R.string.lpa_not_installed);
        }
        List<Line> device = Arrays.asList(
                new Line(ctx.getString(R.string.info_device), Build.MANUFACTURER + " " + Build.MODEL, null, false),
                new Line(ctx.getString(R.string.info_android),
                        ctx.getString(R.string.info_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT), null, true),
                new Line(ctx.getString(R.string.info_build), Build.DISPLAY, null, true),
                new Line(ctx.getString(R.string.info_module), ProbeInspector.MODULE_VERSION, null, true),
                new Line(ctx.getString(R.string.info_lpa), lpaVer, null, true));

        List<Line> status = new ArrayList<>();
        try {
            boolean lpaPrivMounted = ProbeInspector.isLpaPrivMounted(pm);
            boolean hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
            EuiccManager em = ctx.getSystemService(EuiccManager.class);
            boolean enabled = em != null && em.isEnabled();
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            int defaultCardId = ProbeInspector.defaultEuiccCardId(tm);
            boolean lpaSchemeOk = pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SAMPLE_LPA_CODE)), 0) != null;
            boolean startActOk = pm.resolveActivity(new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION), 0) != null;
            int cardType = Settings.Secure.getInt(ctx.getContentResolver(), KEY_CARD_TYPE, -1);

            status.add(new Line(ctx.getString(R.string.st_mount),
                    ctx.getString(lpaPrivMounted ? R.string.st_mount_ok : R.string.st_mount_bad), lpaPrivMounted, false));
            status.add(new Line(ctx.getString(R.string.st_feature),
                    ctx.getString(hasFeature ? R.string.st_feature_yes : R.string.st_feature_no), hasFeature, false));
            status.add(new Line(ctx.getString(R.string.st_enabled),
                    ctx.getString(enabled ? R.string.st_enabled_yes : R.string.st_enabled_no), enabled, false));
            status.add(new Line(ctx.getString(R.string.st_card_id),
                    defaultCardId >= 0 ? String.valueOf(defaultCardId) : "—", defaultCardId >= 0, true));
            status.add(new Line(ctx.getString(R.string.st_card),
                    describeCard(ctx, cardType, lpaPrivMounted, tm), cardType == 1, false));
            status.add(new Line(ctx.getString(R.string.st_lpa_scheme),
                    ctx.getString(lpaSchemeOk ? R.string.st_lpa_scheme_on : R.string.st_lpa_scheme_off), lpaSchemeOk, false));
            status.add(new Line(ctx.getString(R.string.st_activation),
                    ctx.getString(startActOk ? R.string.st_activation_ok : R.string.st_activation_bad), startActOk, false));
        } catch (Throwable t) {
            status.clear();
            status.add(new Line(ctx.getString(R.string.st_mount),
                    ctx.getString(R.string.st_error, String.valueOf(t.getMessage())), false, false));
        }
        return Arrays.asList(
                new Section(ctx.getString(R.string.section_device), device),
                new Section(ctx.getString(R.string.tester_status_title), status));
    }

    private static String describeCard(Context ctx, int cardType, boolean lpaPrivMounted, TelephonyManager tm) {
        int esimSwitch = Settings.Secure.getInt(ctx.getContentResolver(), KEY_ENABLE_ESIM, -1);
        int sim2State = TelephonyManager.SIM_STATE_UNKNOWN;
        if (tm != null) {
            try {
                sim2State = tm.getSimState(1);
            } catch (Throwable ignored) {
            }
        }
        if (cardType == 1) {
            if (sim2State == TelephonyManager.SIM_STATE_READY || sim2State == 10) {
                return ctx.getString(R.string.card_esim_active);
            }
            return ctx.getString(esimSwitch == 0 ? R.string.card_esim_switch_off : R.string.card_esim_empty);
        }
        if (cardType == 0) {
            return ctx.getString(sim2State == TelephonyManager.SIM_STATE_ABSENT ? R.string.card_absent : R.string.card_regular);
        }
        return ctx.getString(lpaPrivMounted ? R.string.card_detecting : R.string.card_wait_mount);
    }
}
