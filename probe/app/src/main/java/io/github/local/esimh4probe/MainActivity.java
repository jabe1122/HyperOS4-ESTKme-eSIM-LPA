package io.github.local.esimh4probe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import io.github.local.esimh4ui.BottomBar;
import io.github.local.esimh4ui.LanguageSetting;
import io.github.local.esimh4ui.RefreshScrollView;
import io.github.local.esimh4ui.Scaffold;
import io.github.local.esimh4ui.StatusBanner;
import io.github.local.esimh4ui.ThemedActivity;
import io.github.local.esimh4ui.UiKit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * Control panel for the Xiaomi HyperOS 4 removable eSIM (SIM2) bridge.
 * Home tab: status banner and live diagnostics. Test tab (optional, see {@link TesterPage}): mock
 * carrier install checks. Settings tab: feature switches, theme and about.
 *
 * <p>The Miuix style shows the Compose screen ({@code MainScreen.kt}); the Material 3 style shows
 * the View pages below. Both read {@link DashboardModel} and change settings through
 * {@link #requestSwitch}.
 */
public final class MainActivity extends ThemedActivity {
    private static final String TAG = "EsimH4Probe";
    private static final String ACTION_SCRIPT = "/data/adb/modules/XiaomiEsimLPAHyperOS4/action.sh";
    /** Saves the selected page id (not its index), because the Test tab can be hidden. */
    private static final String STATE_PAGE = "page";
    static final int PAGE_HOME = 0;
    static final int PAGE_TESTER = 1;
    static final int PAGE_SETTINGS = 2;

    // Settings switches, see requestSwitch
    static final String SW_MASTER = "master";
    static final String SW_SYSTEM_ESIM = "system_esim";
    static final String SW_CARRIER_BRIDGE = "carrier_bridge";
    static final String SW_LPA_URI_BRIDGE = "lpa_uri_bridge";
    static final String SW_REWRITE_TEXT = "rewrite_text";
    static final String SW_UMOUNT_GUARD = "umount_guard";
    static final String SW_ESTK_SE1 = "estk_se1";
    private static final int TAB_HOME = 0;

    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EsimH4-UIWorker");
        t.setDaemon(true);
        return t;
    });

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    /** Callbacks waiting for the current dashboard refresh; touched on the UI thread only. */
    private final List<Runnable> refreshDone = new ArrayList<>();
    private boolean bindingUi = false;
    private JSONObject lastReport = null;

    /** Miuix style: the Compose screen's state; null in the Material 3 style. */
    private MainUiState composeUi;
    private Toast toast;

    private Scaffold scaffold;
    private BottomBar bottomBar;
    /** Page id of each tab, in bottom bar order. */
    private int[] pageIds;
    private int currentTab = TAB_HOME;
    /** Null while the Test tab is hidden. */
    private TesterPage testerPage;
    private final Runnable recreateForTesterTab = this::recreate;
    /** Back on any tab but home returns to the home tab; it is enabled only there. */
    private final OnBackPressedCallback backToHome = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            scaffold.showPage(TAB_HOME, true);
        }
    };

    // Home: status banner
    private StatusBanner statusBanner;

    // Home: alert banner
    private LinearLayout cardAlertBanner;
    private TextView tvAlertMessage;

    // Home: SIM2 hardware & framework card
    private TextView tvSim2Meta;
    private TextView tvSim2Badge;
    private TextView tvBadgeMount;
    private TextView tvBadgePhoneHook;
    private TextView tvBadgeLpaHook;
    private TextView tvBadgeEuicc;
    private LinearLayout btnManageEsim;

    // Settings
    private UiKit.SwitchRow rowMaster;
    private UiKit.SwitchRow rowSystemEsim;
    private UiKit.SwitchRow rowCarrierBridge;
    private UiKit.SwitchRow rowLpaUriBridge;
    private UiKit.SwitchRow rowRewriteText;
    private UiKit.SwitchRow rowUmountGuard;
    private UiKit.SwitchRow rowTesterApp;
    private UiKit.SwitchRow rowEstkSe1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LaunchAnimation launchAnimation = LaunchAnimation.install(this);
        refreshDone.add(launchAnimation::markReady);
        boolean showTester = TesterPage.isEnabled(this);
        int savedPage = savedInstanceState != null ? savedInstanceState.getInt(STATE_PAGE, PAGE_HOME) : PAGE_HOME;
        boolean autoTest = showTester && savedInstanceState == null
                && getIntent() != null && getIntent().getBooleanExtra("auto_test", false);
        if (autoTest) savedPage = PAGE_TESTER;
        if (savedPage == PAGE_TESTER && !showTester) savedPage = PAGE_HOME;

        if (UiKit.isMiuix()) {
            if (showTester) {
                testerPage = new TesterPage(this, palette);
                testerPage.start();
            }
            composeUi = new MainUiState(showTester, savedPage);
            UiKit.applyEdgeToEdge(this, palette);
            MainScreen.setMainContent(this, composeUi);
            refreshDashboard();
            if (autoTest) {
                uiHandler.postDelayed(() -> {
                    if (testerPage != null) testerPage.startMockDownload(TesterPage.DEFAULT_MOCK_CODE);
                }, 400);
            }
            return;
        }

        pageIds = showTester
                ? new int[]{PAGE_HOME, PAGE_TESTER, PAGE_SETTINGS}
                : new int[]{PAGE_HOME, PAGE_SETTINGS};
        currentTab = Math.max(TAB_HOME, tabOf(savedPage));

        scaffold = new Scaffold(this, palette, getString(R.string.app_name), null, false);
        RefreshScrollView homeScroll = scaffold.addPage(
                buildHomePage(), io.github.local.esimh4ui.R.id.ui_page_1, getString(R.string.app_name));
        homeScroll.setOnRefreshListener(() -> refreshDashboard(() -> {
            homeScroll.setRefreshing(false);
            showToast(getString(R.string.snack_refreshed));
        }));
        if (showTester) {
            testerPage = new TesterPage(this, palette);
            RefreshScrollView testerScroll = scaffold.addPage(
                    testerPage.build(), io.github.local.esimh4ui.R.id.ui_page_3, getString(R.string.tester_title));
            testerScroll.setOnRefreshListener(() -> {
                testerPage.refreshStatus();
                // The status is read at once; keep the ring visible briefly so the pull registers.
                testerScroll.postDelayed(() -> testerScroll.setRefreshing(false), 400);
            });
        }
        scaffold.addPage(buildSettingsPage(), io.github.local.esimh4ui.R.id.ui_page_2, getString(R.string.tab_settings));

        bottomBar = showTester
                ? new BottomBar(
                        this,
                        palette,
                        new String[]{getString(R.string.tab_home), getString(R.string.tab_tester),
                                getString(R.string.tab_settings)},
                        new int[]{R.drawable.ic_home_24, R.drawable.ic_science_24, R.drawable.ic_settings_24})
                : new BottomBar(
                        this,
                        palette,
                        new String[]{getString(R.string.tab_home), getString(R.string.tab_settings)},
                        new int[]{R.drawable.ic_home_24, R.drawable.ic_settings_24});
        bottomBar.setOnSelectListener(tab -> scaffold.showPage(tab, true));
        scaffold.setBottomBar(bottomBar);
        scaffold.setOnPageChangeListener(this::onPageSelected);

        getOnBackPressedDispatcher().addCallback(this, backToHome);

        setContentView(scaffold.root);
        UiKit.applyEdgeToEdge(this, palette);
        setupSwitches();
        scaffold.showPage(currentTab, false);
        onPageSelected(currentTab);
        refreshDashboard();
        if (autoTest) {
            scaffold.root.postDelayed(() -> {
                if (testerPage != null) testerPage.startMockDownload(TesterPage.DEFAULT_MOCK_CODE);
            }, 400);
        }
    }

    // --- for the Compose screen (MainScreen.kt) -------------------------------------------------

    /** The theme colors (the field is protected). */
    UiKit.Palette paletteColors() {
        return palette;
    }

    /** The Test tab's logic, or null while the tab is hidden. */
    TesterPage testerPage() {
        return testerPage;
    }

    void openMobileNetwork() {
        try {
            startActivity(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS));
        } catch (Throwable e) {
            showToast(getString(R.string.snack_mobile_network_failed));
        }
    }

    void openDeviceStatus() {
        startActivity(new Intent(this, DeviceStatusActivity.class));
    }

    void openAbout() {
        startActivity(new Intent(this, AboutActivity.class));
    }

    void openTheme() {
        openThemeSettings(false);
    }

    /** Index of the tab that shows {@code pageId}, or -1 when it is hidden. */
    private int tabOf(int pageId) {
        for (int i = 0; i < pageIds.length; i++) {
            if (pageIds[i] == pageId) return i;
        }
        return -1;
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateChecker.checkOnLaunch(this);
        if (isThemeRecreating()) return;
        refreshDashboard();
        if (testerPage != null) testerPage.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, composeUi != null ? composeUi.getCurrentPage() : pageIds[currentTab]);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (testerPage != null) testerPage.onActivityResult(requestCode, resultCode);
    }

    @Override
    protected void onDestroy() {
        if (testerPage != null) testerPage.release();
        if (scaffold != null) scaffold.release();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Called when the pager settles on a page (swipe or bottom bar tap). */
    private void onPageSelected(int tab) {
        currentTab = tab;
        bottomBar.select(tab);
        int page = pageIds[tab];
        scaffold.setTitle(getString(page == PAGE_HOME ? R.string.app_name
                : page == PAGE_TESTER ? R.string.tester_title : R.string.tab_settings));
        backToHome.setEnabled(tab != TAB_HOME);
    }

    // ---------------------------------------------------------------------------------------------
    // Home page
    // ---------------------------------------------------------------------------------------------

    private View buildHomePage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(buildStatusBanner());

        // Alert banner (shown only when something needs attention)
        cardAlertBanner = new LinearLayout(this);
        cardAlertBanner.setOrientation(LinearLayout.HORIZONTAL);
        cardAlertBanner.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams alertLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alertLp.topMargin = UiKit.dp(this, 10);
        cardAlertBanner.setLayoutParams(alertLp);
        int alertPad = UiKit.dp(this, 16);
        cardAlertBanner.setPadding(alertPad, UiKit.dp(this, 14), alertPad, UiKit.dp(this, 14));
        cardAlertBanner.setBackground(UiKit.roundedRect(this, palette.errorContainer, UiKit.radius(20f, 16f), 0, 0));
        cardAlertBanner.setVisibility(View.GONE);

        ImageView ivAlert = new ImageView(this);
        int alertIconPx = UiKit.dp(this, 20);
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(alertIconPx, alertIconPx);
        aiLp.rightMargin = UiKit.dp(this, 12);
        aiLp.topMargin = UiKit.dp(this, 2);
        ivAlert.setLayoutParams(aiLp);
        ivAlert.setImageResource(R.drawable.ic_error_24);
        ivAlert.setImageTintList(ColorStateList.valueOf(palette.onErrorContainer));
        cardAlertBanner.addView(ivAlert);

        tvAlertMessage = new TextView(this);
        tvAlertMessage.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvAlertMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f);
        tvAlertMessage.setLineSpacing(UiKit.dpf(this, 2.5f), 1.0f);
        tvAlertMessage.setTextColor(palette.onErrorContainer);
        cardAlertBanner.addView(tvAlertMessage);
        col.addView(cardAlertBanner);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_health)));
        col.addView(buildSimCard());

        return col;
    }

    private final HiddenEntryGesture diagnosticsGesture = new HiddenEntryGesture();

    void onProbeStatusTap() {
        if (diagnosticsGesture.tap(this, R.string.diag_taps_remaining, R.string.diag_opened)) {
            showDiagnosticsDialog();
        }
    }

    /** SukiSU-style status banner: green when running, red when off or needing attention. */
    private View buildStatusBanner() {
        statusBanner = new StatusBanner(this, palette);
        statusBanner.setOnClickListener(v -> onProbeStatusTap());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 4);
        statusBanner.setLayoutParams(lp);
        statusBanner.showLoading(getString(R.string.status_loading));
        return statusBanner;
    }

    private View buildSimCard() {
        LinearLayout simCard = UiKit.createCard(this, palette);

        LinearLayout simHead = new LinearLayout(this);
        simHead.setOrientation(LinearLayout.HORIZONTAL);
        simHead.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout simIconBox = new FrameLayout(this);
        int simBoxPx = UiKit.dp(this, 44);
        LinearLayout.LayoutParams sibLp = new LinearLayout.LayoutParams(simBoxPx, simBoxPx);
        sibLp.rightMargin = UiKit.dp(this, 14);
        simIconBox.setLayoutParams(sibLp);
        simIconBox.setBackground(UiKit.roundedRect(this, palette.secondaryContainer, UiKit.radius(14f, 12f), 0, 0));

        ImageView ivSim = new ImageView(this);
        int simIcPx = UiKit.dp(this, 22);
        ivSim.setLayoutParams(new FrameLayout.LayoutParams(simIcPx, simIcPx, Gravity.CENTER));
        ivSim.setImageResource(R.drawable.ic_sim_card_24);
        ivSim.setImageTintList(ColorStateList.valueOf(palette.onSecondaryContainer));
        simIconBox.addView(ivSim);
        simHead.addView(simIconBox);

        LinearLayout simTitleCol = new LinearLayout(this);
        simTitleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stcLp.rightMargin = UiKit.dp(this, 8);
        simTitleCol.setLayoutParams(stcLp);

        TextView tvSimTitle = new TextView(this);
        tvSimTitle.setText(R.string.sim2_title);
        tvSimTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvSimTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvSimTitle.setTextColor(palette.onSurface);
        simTitleCol.addView(tvSimTitle);

        tvSim2Meta = new TextView(this);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = UiKit.dp(this, 2);
        tvSim2Meta.setLayoutParams(metaLp);
        tvSim2Meta.setText(getString(R.string.sim2_carrier, "…"));
        tvSim2Meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSim2Meta.setTextColor(palette.onSurfaceVariant);
        simTitleCol.addView(tvSim2Meta);
        simHead.addView(simTitleCol);

        tvSim2Badge = new TextView(this);
        tvSim2Badge.setText(R.string.badge_checking);
        tvSim2Badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSim2Badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvSim2Badge.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 5), UiKit.dp(this, 11), UiKit.dp(this, 5));
        simHead.addView(tvSim2Badge);
        simCard.addView(simHead);

        // 2x2 health grid
        LinearLayout gridRow1 = new LinearLayout(this);
        gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gr1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gr1Lp.topMargin = UiKit.dp(this, 14);
        gridRow1.setLayoutParams(gr1Lp);
        tvBadgeMount = addHealthTile(gridRow1, getString(R.string.tile_mount), true);
        tvBadgePhoneHook = addHealthTile(gridRow1, getString(R.string.tile_phone_hook), false);
        simCard.addView(gridRow1);

        LinearLayout gridRow2 = new LinearLayout(this);
        gridRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gr2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gr2Lp.topMargin = UiKit.dp(this, 8);
        gridRow2.setLayoutParams(gr2Lp);
        tvBadgeLpaHook = addHealthTile(gridRow2, getString(R.string.tile_lpa_hook), true);
        tvBadgeEuicc = addHealthTile(gridRow2, getString(R.string.tile_euicc), false);
        simCard.addView(gridRow2);

        LinearLayout btnReprobe = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.TONAL, R.drawable.ic_refresh_24,
                getString(R.string.btn_reprobe), 44, this::triggerReprobeSim2);
        LinearLayout.LayoutParams reprobeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reprobeLp.topMargin = UiKit.dp(this, 16);
        simCard.addView(btnReprobe, reprobeLp);

        btnManageEsim = UiKit.createActionButton(
                this, palette, UiKit.ButtonStyle.FILLED, R.drawable.ic_qr_code_scanner_24,
                getString(R.string.btn_manage_esim), 48, this::openNativeEsimManager);
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        manageLp.topMargin = UiKit.dp(this, 10);
        simCard.addView(btnManageEsim, manageLp);

        return simCard;
    }

    private TextView addHealthTile(LinearLayout parentRow, String label, boolean isLeft) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (isLeft) {
            lp.rightMargin = UiKit.dp(this, 4);
        } else {
            lp.leftMargin = UiKit.dp(this, 4);
        }
        tile.setLayoutParams(lp);
        int padH = UiKit.dp(this, 12);
        int padV = UiKit.dp(this, 10);
        tile.setPadding(padH, padV, padH, padV);
        tile.setBackground(UiKit.roundedRect(this, palette.tileBg, UiKit.radius(14f, 12f), 0, 0));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvLabel.setTextColor(palette.onSurfaceVariant);
        tile.addView(tvLabel);

        TextView tvVal = new TextView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = UiKit.dp(this, 3);
        tvVal.setLayoutParams(vlp);
        tvVal.setText("○ " + getString(R.string.badge_checking));
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvVal.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvVal.setTextColor(palette.onSurface);
        tile.addView(tvVal);

        parentRow.addView(tile);
        return tvVal;
    }

    // ---------------------------------------------------------------------------------------------
    // Settings page
    // ---------------------------------------------------------------------------------------------

    private View buildSettingsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        rowMaster = new UiKit.SwitchRow(
                this, palette, R.drawable.ic_power_24,
                getString(R.string.row_master), getString(R.string.status_loading_summary), 24f, 24f, 0);
        col.addView(rowMaster.card);
        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_core)));
        rowSystemEsim = new UiKit.SwitchRow(this, palette, R.drawable.ic_sync_alt_24,
                getString(R.string.row_system_esim), getString(R.string.row_system_esim_summary), 24f, 6f, 2);
        col.addView(rowSystemEsim.card);
        rowCarrierBridge = new UiKit.SwitchRow(this, palette, R.drawable.ic_verified_user_24,
                getString(R.string.row_carrier_bridge), getString(R.string.row_carrier_bridge_summary), 6f, 6f, 2);
        col.addView(rowCarrierBridge.card);
        rowLpaUriBridge = new UiKit.SwitchRow(this, palette, R.drawable.ic_link_24,
                getString(R.string.row_lpa_uri), getString(R.string.row_lpa_uri_summary), 6f, 6f, 2);
        col.addView(rowLpaUriBridge.card);
        rowRewriteText = new UiKit.SwitchRow(this, palette, R.drawable.ic_translate_24,
                getString(R.string.row_rewrite_text), getString(R.string.row_rewrite_text_summary), 6f, 24f, 0);
        col.addView(rowRewriteText.card);

        col.addView(UiKit.createSectionHeader(this, palette, getString(R.string.section_root)));
        rowUmountGuard = new UiKit.SwitchRow(this, palette, R.drawable.ic_shield_24,
                getString(R.string.row_umount_guard), getString(R.string.row_umount_guard_summary), 24f, 6f, 2);
        col.addView(rowUmountGuard.card);
        rowEstkSe1 = new UiKit.SwitchRow(this, palette, R.drawable.ic_sync_alt_24,
                getString(R.string.row_estk_se1), getString(R.string.row_estk_se1_summary), 6f, 24f, 0);
        col.addView(rowEstkSe1.card);

        UiKit.SwitchRow startupRow = new UiKit.SwitchRow(this, palette, R.drawable.ic_link_24,
                getString(R.string.update_startup), null, 24f, 6f, 2);
        startupRow.setRowState(UpdateChecker.checkAtStartup(this), true, null);
        startupRow.toggle.setOnCheckedChangeListener((view, enabled) -> {
            UpdateChecker.setCheckAtStartup(this, enabled);
            startupRow.refreshIconTint(enabled);
        });
        ((LinearLayout.LayoutParams) startupRow.card.getLayoutParams()).topMargin = UiKit.dp(this, 10);
        col.addView(startupRow.card);

        UiKit.NavRow channelRow = new UiKit.NavRow(
                this, palette, R.drawable.ic_link_24, getString(R.string.update_channel),
                null, 6f, 6f, 2, null);
        channelRow.setValue(UpdateChecker.isNightly(this) ? "Nightly" : "Stable");
        channelRow.card.setOnClickListener(v -> UiKit.showChoiceDialog(this, palette,
                getString(R.string.update_channel), new String[]{"Stable", "Nightly"},
                UpdateChecker.isNightly(this) ? 1 : 0, index -> {
                    UpdateChecker.setNightly(this, index == 1);
                    channelRow.setValue(index == 1 ? "Nightly" : "Stable");
                }));
        col.addView(channelRow.card);
        UiKit.SwitchRow installRow = new UiKit.SwitchRow(this, palette, R.drawable.ic_link_24,
                getString(R.string.update_auto_install), null, 6f, 6f, 2);
        installRow.setRowState(UpdateChecker.autoInstall(this), true, null);
        col.addView(installRow.card);
        UiKit.NavRow managerRow = new UiKit.NavRow(this, palette, R.drawable.ic_link_24,
                getString(R.string.update_manager), null, 6f, 24f, 0, null);
        managerRow.setValue(UpdateChecker.managerLabel(this));
        managerRow.card.setVisibility(UpdateChecker.autoInstall(this) ? View.VISIBLE : View.GONE);
        managerRow.card.setOnClickListener(v -> {
            java.util.List<UpdateChecker.Manager> managers = UpdateChecker.installedManagers(this);
            if (managers.isEmpty()) {
                android.widget.Toast.makeText(this, R.string.update_manager_none,
                        android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            String[] labels = new String[managers.size()];
            int selected = -1;
            for (int i = 0; i < managers.size(); i++) {
                labels[i] = managers.get(i).name;
                if (managers.get(i).packageName.equals(UpdateChecker.selectedManager(this))) selected = i;
            }
            UiKit.showChoiceDialog(this, palette, getString(R.string.update_manager), labels, selected, index -> {
                UpdateChecker.setSelectedManager(this, managers.get(index).packageName);
                managerRow.setValue(managers.get(index).name);
            });
        });
        col.addView(managerRow.card);
        installRow.toggle.setOnCheckedChangeListener((view, enabled) -> {
            UpdateChecker.setAutoInstall(this, enabled);
            installRow.refreshIconTint(enabled);
            android.transition.TransitionManager.beginDelayedTransition(col,
                    new android.transition.AutoTransition().setDuration(250));
            managerRow.setValue(UpdateChecker.managerLabel(this));
            managerRow.card.setVisibility(enabled ? View.VISIBLE : View.GONE);
        });

        rowTesterApp = new UiKit.SwitchRow(this, palette, R.drawable.ic_science_24,
                getString(R.string.row_tester), getString(R.string.row_tester_summary), 24f, 24f, 0);
        col.addView(rowTesterApp.card);

        UiKit.NavRow rowTheme = new UiKit.NavRow(
                this, palette, io.github.local.esimh4ui.R.drawable.ic_palette_24,
                getString(R.string.section_personalize), getString(R.string.row_theme_summary),
                24f, 24f, 0, () -> openThemeSettings(true));
        rowTheme.setValue(getString(UiKit.isMiuix()
                ? io.github.local.esimh4ui.R.string.theme_style_miuix
                : io.github.local.esimh4ui.R.string.theme_style_material3));
        col.addView(rowTheme.card);

        UiKit.NavRow rowDeviceStatus = new UiKit.NavRow(
                this, palette, R.drawable.ic_monitor_heart_24, getString(R.string.row_device_status),
                getString(R.string.row_device_status_summary),
                24f, 24f, 0, this::openDeviceStatus);
        col.addView(rowDeviceStatus.card);

        UiKit.NavRow rowAbout = new UiKit.NavRow(
                this, palette, R.drawable.ic_info_24, getString(R.string.section_about), null,
                24f, 24f, 0, this::openAbout);
        rowAbout.setValue(AboutInfo.versionName(this));
        LinearLayout.LayoutParams aboutLp = (LinearLayout.LayoutParams) rowAbout.card.getLayoutParams();
        aboutLp.topMargin = UiKit.dp(this, 16);
        col.addView(rowAbout.card);
        return col;
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    void openNativeEsimManager() {
        if (lastReport != null) {
            if (!lastReport.optBoolean("moduleActive", true)) {
                showToast(getString(R.string.snack_enable_master_first));
                return;
            }
            if (!lastReport.optBoolean("lpaPrivMounted", true)) {
                showToast(getString(R.string.snack_lpa_not_mounted));
                return;
            }
            int cardType = lastReport.optInt("cardType", -1);
            if (cardType == 0) {
                showToast(getString(R.string.snack_no_esim_card));
                return;
            }
            int esimSwitch = lastReport.optInt("esimSwitch", 1);
            if (cardType == 1 && esimSwitch == 0) {
                UiKit.showDialog(
                        this,
                        palette,
                        getString(R.string.dialog_esim_off_title),
                        getString(R.string.dialog_esim_off_message),
                        false,
                        getString(R.string.dialog_esim_off_confirm),
                        () -> {
                            setBusy(true);
                            IO_EXEC.execute(() -> {
                                Bundle extras = new Bundle();
                                extras.putBoolean("enabled", true);
                                Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "set_esim_state", extras);
                                boolean ok = res != null && res.getBoolean("ok", false);
                                runOnUiThread(() -> {
                                    setBusy(false);
                                    if (ok) {
                                        refreshDashboard();
                                        launchMiuiEsimProfileList();
                                    } else {
                                        showToast(getString(R.string.snack_wake_failed));
                                    }
                                });
                            });
                        },
                        getString(io.github.local.esimh4ui.R.string.ui_cancel));
                return;
            }
        }
        launchMiuiEsimProfileList();
    }

    private void launchMiuiEsimProfileList() {
        // Primary: direct foreground launch of MIUIEsimLPA's exported ProfileListActivity
        // (exported=true with no MODIFY_PHONE_STATE / BIND_EUICC_SERVICE permission gate,
        // avoiding EuiccUiDispatcherActivity's SecurityException for normal app UIDs while
        // preserving foreground BAL_ALLOW_VISIBLE_WINDOW transition).
        try {
            Intent direct = new Intent();
            direct.setComponent(new ComponentName(
                    ProbeInspector.LPA_PKG,
                    "com.miui.euicc.ui.main.ProfileListActivity"));
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(direct);
            return;
        } catch (Throwable primaryErr) {
            Log.w(TAG, "Direct ProfileListActivity launch failed, trying phone dispatcher", primaryErr);
        }

        // Fallback: ask privileged com.android.phone (uid 1001, holds MODIFY_PHONE_STATE & BIND_EUICC_SERVICE)
        // to dispatch ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS.
        IO_EXEC.execute(() -> {
            Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "open_esim_manager", null);
            boolean ok = res != null && res.getBoolean("ok", false);
            if (!ok) {
                String err = res != null ? res.getString("error", "") : "";
                runOnUiThread(() -> showToast(err.isEmpty()
                        ? getString(R.string.snack_open_lpa_failed)
                        : getString(R.string.snack_open_lpa_failed_detail, err)));
            }
        });
    }

    void triggerReprobeSim2() {
        setBusy(true);
        IO_EXEC.execute(() -> {
            Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "reprobe", null);
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            runOnUiThread(() -> {
                setBusy(false);
                boolean ok = res != null && res.getBoolean("ok", false);
                showToast(getString(ok ? R.string.snack_reprobe_ok : R.string.snack_reprobe_failed));
                refreshDashboard();
            });
        });
    }

    private void bindRowClick(UiKit.SwitchRow row, String disabledHint) {
        row.card.setOnClickListener(v -> {
            if (row.toggle.isEnabled()) {
                row.toggle.performUserToggle();
            } else if (disabledHint != null) {
                showToast(disabledHint);
            }
        });
    }

    private void setupSwitches() {
        String hintHook = getString(R.string.hint_need_hook);
        String hintMaster = getString(R.string.hint_need_master);
        bindRowClick(rowMaster, null);
        bindRowClick(rowSystemEsim, getString(R.string.hint_need_esim_card));
        bindRowClick(rowCarrierBridge, hintHook);
        bindRowClick(rowLpaUriBridge, null);
        bindRowClick(rowRewriteText, hintHook);
        bindRowClick(rowUmountGuard, getString(R.string.hint_need_mount));
        bindRowClick(rowTesterApp, null);
        bindRowClick(rowEstkSe1, hintMaster);

        bindSwitch(rowMaster, SW_MASTER);
        bindSwitch(rowSystemEsim, SW_SYSTEM_ESIM);
        bindSwitch(rowCarrierBridge, SW_CARRIER_BRIDGE);
        bindSwitch(rowLpaUriBridge, SW_LPA_URI_BRIDGE);
        bindSwitch(rowRewriteText, SW_REWRITE_TEXT);
        bindSwitch(rowUmountGuard, SW_UMOUNT_GUARD);
        bindSwitch(rowEstkSe1, SW_ESTK_SE1);

        // App-local setting: the bottom bar gains or loses the Test tab.
        boolean testerShown = testerPage != null;
        rowTesterApp.setRowState(testerShown, true,
                getString(testerShown ? R.string.tester_sub_on : R.string.tester_sub_off));
        rowTesterApp.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowTesterApp.refreshIconTint(isChecked);
            setTesterTabShown(isChecked);
        });
    }

    /** View page: a switch row runs {@link #requestSwitch} and flips back when it fails. */
    private void bindSwitch(UiKit.SwitchRow row, String key) {
        row.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            row.refreshIconTint(isChecked);
            row.toggle.setEnabled(false);
            requestSwitch(key, isChecked, ok -> {
                row.toggle.setEnabled(true);
                if (!ok) setSwitchSilently(row.toggle, row, !isChecked);
            });
        });
    }

    /**
     * Applies a settings switch ({@code SW_*}) for either UI. {@code done} runs on the UI thread
     * with false when the change failed, so the caller can show the old state again; on success
     * the dashboard is refreshed and the next report shows the new state.
     */
    void requestSwitch(String key, boolean on, Consumer<Boolean> done) {
        switch (key) {
            case SW_MASTER:
                runRootAction(new String[]{on ? "on" : "off"}, done, output -> {
                    if (output.contains("RESULT=MISSING_METAMODULE")) {
                        showInfoDialog(getString(R.string.dialog_missing_metamodule_title),
                                getString(R.string.dialog_missing_metamodule_message));
                    } else if (output.contains("RESULT=NEED_REBOOT")) {
                        showInfoDialog(getString(R.string.dialog_need_reboot_title),
                                getString(R.string.dialog_need_reboot_message));
                    } else if (output.contains("RESULT=ACTIVE_NOW")) {
                        showToast(getString(R.string.snack_master_on));
                    } else if (output.contains("RESULT=OFF_APPLIED")) {
                        showInfoDialog(getString(R.string.dialog_master_off_title),
                                getString(R.string.dialog_master_off_message));
                    } else {
                        showToast(getString(on ? R.string.snack_master_on_sent : R.string.snack_master_off_sent));
                    }
                    refreshDashboard();
                });
                break;
            case SW_SYSTEM_ESIM:
                setBusy(true);
                IO_EXEC.execute(() -> {
                    Bundle extras = new Bundle();
                    extras.putBoolean("enabled", on);
                    Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "set_esim_state", extras);
                    boolean ok = res != null && res.getBoolean("ok", false);
                    runOnUiThread(() -> {
                        setBusy(false);
                        done.accept(ok);
                        if (!ok) {
                            showToast(getString(R.string.snack_esim_toggle_failed));
                        } else {
                            showToast(getString(on ? R.string.snack_esim_on : R.string.snack_esim_off));
                            refreshDashboard();
                        }
                    });
                });
                break;
            case SW_CARRIER_BRIDGE:
                updatePhonePref(CardTypeGuard.KEY_CARRIER_BRIDGE, on,
                        getString(on ? R.string.snack_carrier_on : R.string.snack_carrier_off), done);
                break;
            case SW_REWRITE_TEXT:
                updatePhonePref(CardTypeGuard.KEY_REWRITE_TEXT, on,
                        getString(on ? R.string.snack_rewrite_on : R.string.snack_rewrite_off), done);
                break;
            case SW_LPA_URI_BRIDGE:
                try {
                    ComponentName comp = new ComponentName(this, LpaBridgeActivity.class);
                    getPackageManager().setComponentEnabledSetting(
                            comp,
                            on
                                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                } catch (Throwable e) {
                    done.accept(false);
                    showToast(getString(R.string.snack_component_failed, e.getClass().getSimpleName()));
                    return;
                }
                IO_EXEC.execute(() -> {
                    Bundle extras = new Bundle();
                    extras.putString("key", CardTypeGuard.KEY_LPA_URI_BRIDGE);
                    extras.putBoolean("enabled", on);
                    ProbeInspector.callPhoneCtrl(MainActivity.this, "set_pref", extras);
                    runOnUiThread(() -> {
                        done.accept(true);
                        showToast(getString(on ? R.string.snack_lpa_uri_on : R.string.snack_lpa_uri_off));
                        refreshDashboard();
                    });
                });
                break;
            case SW_UMOUNT_GUARD:
                runRootAction(new String[]{"umount_guard", on ? "on" : "off"}, done, output -> {
                    showToast(getString(on ? R.string.snack_umount_on : R.string.snack_umount_off));
                    refreshDashboard();
                });
                break;
            case SW_ESTK_SE1:
                runRootAction(new String[]{"isdr_se", on ? "se1" : "auto"}, done, output -> {
                    showInfoDialog(
                            getString(on ? R.string.dialog_se1_title : R.string.dialog_se0_title),
                            getString(R.string.dialog_se_message));
                    refreshDashboard();
                });
                break;
            default:
                done.accept(false);
                break;
        }
    }

    /**
     * Shows or hides the Test tab. The View pages rebuild the screen; the Compose pages add or
     * remove the tab in place.
     */
    void setTesterTabShown(boolean on) {
        TesterPage.setEnabled(this, on);
        showToast(getString(on ? R.string.snack_tester_on : R.string.snack_tester_off));
        if (composeUi != null) {
            if (on && testerPage == null) {
                testerPage = new TesterPage(this, palette);
                testerPage.start();
            } else if (!on && testerPage != null) {
                testerPage.release();
                testerPage = null;
            }
            composeUi.setShowTester(on);
            return;
        }
        // Let the switch finish its animation first.
        uiHandler.removeCallbacks(recreateForTesterTab);
        uiHandler.postDelayed(recreateForTesterTab, 220);
    }

    private void updatePhonePref(String key, boolean isChecked, String okMsg, Consumer<Boolean> done) {
        IO_EXEC.execute(() -> {
            Bundle extras = new Bundle();
            extras.putString("key", key);
            extras.putBoolean("enabled", isChecked);
            Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "set_pref", extras);
            boolean ok = res != null && res.getBoolean("ok", false);
            runOnUiThread(() -> {
                done.accept(ok);
                if (!ok) {
                    showToast(getString(R.string.snack_pref_failed));
                } else {
                    showToast(okMsg);
                    refreshDashboard();
                }
            });
        });
    }

    private interface ActionCallback {
        void onSuccess(String stdout);
    }

    /** Runs action.sh with root; {@code done} gets false (and a root dialog shows) when it fails. */
    private void runRootAction(String[] args, Consumer<Boolean> done, ActionCallback callback) {
        setBusy(true);
        IO_EXEC.execute(() -> {
            StringBuilder cmd = new StringBuilder("sh ").append(ACTION_SCRIPT);
            for (String a : args) {
                cmd.append(' ').append(a);
            }
            boolean rootOk = false;
            String outText = "";
            try {
                java.lang.Process p = new ProcessBuilder("su", "-c", cmd.toString())
                        .redirectErrorStream(true)
                        .start();
                if (p.waitFor(25, TimeUnit.SECONDS)) {
                    outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    rootOk = (p.exitValue() == 0);
                } else {
                    p.destroyForcibly();
                }
            } catch (Throwable ignored) {
            }
            final boolean success = rootOk;
            final String finalOut = outText;
            runOnUiThread(() -> {
                setBusy(false);
                done.accept(success);
                if (!success) {
                    if (args.length > 0 && SW_UMOUNT_GUARD.equals(args[0])
                            && finalOut.contains("RESULT=ERROR")) {
                        showToast(getString(R.string.snack_component_failed,
                                getString(R.string.row_umount_guard)));
                        showInfoDialog(getString(R.string.row_umount_guard),
                                finalOut.replace("RESULT=ERROR", "").trim());
                        refreshDashboard();
                    } else {
                        showRootPermissionDialog();
                    }
                } else {
                    callback.onSuccess(finalOut);
                }
            });
        });
    }

    private void showRootPermissionDialog() {
        UiKit.showDialog(
                this,
                palette,
                getString(R.string.dialog_root_title),
                getString(R.string.dialog_root_message, getString(R.string.app_name)),
                false,
                null,
                null,
                getString(R.string.dialog_got_it));
    }

    private void showInfoDialog(String title, String message) {
        UiKit.showDialog(this, palette, title, message, false, null, null, null);
    }

    void showDiagnosticsDialog() {
        String pretty;
        try {
            JSONObject obj = lastReport != null
                    ? lastReport
                    : new JSONObject(ProbeInspector.inspect(this));
            pretty = obj.toString(2);
        } catch (Throwable e) {
            pretty = getString(R.string.diag_read_failed, String.valueOf(e.getMessage()));
        }
        final String reportText = pretty;
        UiKit.showDialog(
                this,
                palette,
                getString(R.string.diag_title),
                reportText,
                true,
                getString(R.string.diag_copy),
                () -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("EsimH4Diagnostics", reportText));
                        showToast(getString(R.string.snack_diag_copied));
                    }
                },
                getString(R.string.diag_close));
    }

    private void setBusy(boolean busy) {
        if (composeUi != null) composeUi.setBusy(busy);
        if (scaffold != null) scaffold.setBusy(busy);
    }

    private void setSwitchSilently(UiKit.ToggleSwitch sw, UiKit.SwitchRow ownerRow, boolean checked) {
        boolean prev = bindingUi;
        bindingUi = true;
        try {
            sw.setChecked(checked, true);
            if (ownerRow != null) {
                ownerRow.refreshIconTint(checked);
            }
        } finally {
            bindingUi = prev;
        }
    }

    void showToast(String msg) {
        if (scaffold != null) {
            scaffold.showToast(msg);
            return;
        }
        if (isFinishing() || isDestroyed()) return;
        if (toast != null) toast.cancel();
        toast = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT);
        toast.show();
    }

    // ---------------------------------------------------------------------------------------------
    // Dashboard binding
    // ---------------------------------------------------------------------------------------------

    void refreshDashboard() {
        refreshDashboard(null);
    }

    /** {@code onDone} runs on the UI thread once the running (or a new) refresh has finished. */
    void refreshDashboard(Runnable onDone) {
        if (onDone != null) refreshDone.add(onDone);
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        setBusy(true);
        IO_EXEC.execute(() -> {
            try {
                String result = ProbeInspector.inspect(MainActivity.this);
                Log.i(TAG, result);
                JSONObject report = new JSONObject(result);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    lastReport = report;
                    bindDashboard(report);
                });
            } catch (Throwable e) {
                Log.e(TAG, "inspect failed", e);
            } finally {
                refreshing.set(false);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        setBusy(false);
                    }
                    List<Runnable> done = new ArrayList<>(refreshDone);
                    refreshDone.clear();
                    for (Runnable r : done) r.run();
                });
            }
        });
    }

    private void bindDashboard(JSONObject r) {
        DashboardModel m = new DashboardModel(this, r);
        if (composeUi != null) {
            composeUi.setModel(m);
            return;
        }
        bindingUi = true;
        try {
            // 1. Status banner
            if (m.bannerOk) {
                statusBanner.showOk(m.bannerTitle, m.bannerText, m.bannerTags);
            } else {
                Runnable action = null;
                if (m.bannerAction == DashboardModel.BannerAction.OPEN_SETTINGS) {
                    action = () -> scaffold.showPage(tabOf(PAGE_SETTINGS), true);
                } else if (m.bannerAction == DashboardModel.BannerAction.DIAGNOSTICS) {
                    action = this::showDiagnosticsDialog;
                }
                statusBanner.showError(m.bannerText, m.bannerTitle, m.bannerActionText, action);
            }

            // 2. Alert banner
            UiKit.setAnimatedVisibility(cardAlertBanner, m.alert != null ? View.VISIBLE : View.GONE);
            if (m.alert != null) tvAlertMessage.setText(m.alert);

            // 3. SIM2 card
            tvSim2Meta.setText(m.sim2Meta);
            tvSim2Badge.setText(m.badgeRes);
            tvSim2Badge.setTextColor(getColor(m.badgeFgRes));
            tvSim2Badge.setBackground(UiKit.roundedRect(this, getColor(m.badgeBgRes), 999f, 0, 0));
            setHealthTile(tvBadgeMount, m.tileMount);
            setHealthTile(tvBadgePhoneHook, m.tilePhoneHook);
            setHealthTile(tvBadgeLpaHook, m.tileLpaHook);
            setHealthTile(tvBadgeEuicc, m.tileEuicc);
            UiKit.setButtonEnabled(btnManageEsim, m.manageEnabled);

            // 4. Switches
            bindRow(rowMaster, m.master);
            bindRow(rowSystemEsim, m.systemEsim);
            bindRow(rowCarrierBridge, m.carrierBridge);
            bindRow(rowLpaUriBridge, m.lpaUriBridge);
            bindRow(rowRewriteText, m.rewriteText);
            bindRow(rowUmountGuard, m.umountGuard);
            bindRow(rowEstkSe1, m.estkSe1);
        } finally {
            bindingUi = false;
        }
    }

    private static void bindRow(UiKit.SwitchRow row, DashboardModel.Row state) {
        row.setRowState(state.checked, state.enabled, state.summary);
    }

    private void setHealthTile(TextView tv, DashboardModel.Tile tile) {
        tv.setText(tile.display());
        tv.setTextColor(getColor(tile.colorRes()));
    }

    public static String inspect(Context ctx) {
        return ProbeInspector.inspect(ctx);
    }

    public static void main(String[] args) throws Exception {
        ProbeInspector.main(args);
    }
}
