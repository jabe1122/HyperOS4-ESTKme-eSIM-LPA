package io.github.local.esimh4probe;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Pure OS-native Material Design 3 Expressive control panel and live diagnostics dashboard
 * for Xiaomi HyperOS 4 removable eSIM (SIM2) native LPA bridge.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "EsimH4Probe";
    private static final String ACTION_SCRIPT = "/data/adb/modules/XiaomiEsimLPAHyperOS4/action.sh";

    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EsimH4-UIWorker");
        t.setDaemon(true);
        return t;
    });

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private boolean bindingUi = false;
    private JSONObject lastReport = null;

    private M3Ui.Palette palette;
    private FrameLayout rootOverlay;
    private IndeterminateM3Bar progressBar;
    private TextView snackView;
    private Runnable hideSnackTask;

    // 1. Hero Master Switch Card
    private LinearLayout cardMasterHero;
    private FrameLayout heroIconContainer;
    private ImageView ivHeroIcon;
    private TextView tvHeroTitle;
    private TextView tvHeroSubtitle;
    private M3Ui.M3Switch switchMasterMode;

    // 2. Conditional Alert Banner
    private LinearLayout cardAlertBanner;
    private TextView tvAlertMessage;

    // 3. SIM2 Hardware & Framework Health Card
    private TextView tvSim2Meta;
    private TextView tvSim2Badge;
    private TextView tvSim2Detail;
    private TextView tvBadgeMount;
    private TextView tvBadgePhoneHook;
    private TextView tvBadgeLpaHook;
    private TextView tvBadgeEuicc;
    private LinearLayout btnManageEsim;
    private LinearLayout btnMobileNetwork;
    private LinearLayout btnLaunchTester;

    // 4. Segmented Feature Switch Rows
    private M3Ui.SwitchRow rowSystemEsim;
    private M3Ui.SwitchRow rowCarrierBridge;
    private M3Ui.SwitchRow rowLpaUriBridge;
    private M3Ui.SwitchRow rowRewriteText;
    private M3Ui.SwitchRow rowUmountGuard;
    private M3Ui.SwitchRow rowTesterApp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        palette = M3Ui.Palette.resolve(this);

        setContentView(buildContentView());
        M3Ui.applyEdgeToEdge(this, palette);
        setupSwitches();
        refreshDashboard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
    }

    @Override
    protected void onDestroy() {
        if (progressBar != null) {
            progressBar.stopAnimation();
        }
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View buildContentView() {
        rootOverlay = new FrameLayout(this);
        rootOverlay.setBackgroundColor(palette.pageBg);

        LinearLayout mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        mainCol.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top App Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(palette.pageBg);
        int barPadH = M3Ui.dp(this, 18);
        int barPadV = M3Ui.dp(this, 12);
        topBar.setPadding(barPadH, barPadV, barPadH, barPadV);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams twLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        twLp.rightMargin = M3Ui.dp(this, 10);
        titleWrap.setLayoutParams(twLp);

        TextView tvAppTitle = new TextView(this);
        tvAppTitle.setText(R.string.app_name);
        tvAppTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        tvAppTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvAppTitle.setTextColor(palette.onSurface);
        titleWrap.addView(tvAppTitle);

        TextView tvVerPill = new TextView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = M3Ui.dp(this, 3);
        tvVerPill.setLayoutParams(vlp);
        tvVerPill.setText(ProbeInspector.MODULE_VERSION + " · SIM2 实体卡桥接");
        tvVerPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvVerPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvVerPill.setTextColor(palette.onSecondaryContainer);
        tvVerPill.setPadding(M3Ui.dp(this, 8), M3Ui.dp(this, 2), M3Ui.dp(this, 8), M3Ui.dp(this, 2));
        tvVerPill.setBackground(M3Ui.roundedRect(this, palette.secondaryContainer, 999f, 0, 0));
        titleWrap.addView(tvVerPill);
        topBar.addView(titleWrap);

        ImageView btnRefreshTop = M3Ui.createIconButton(
                this, palette, R.drawable.ic_refresh_24, "刷新底层诊断", () -> {
                    refreshDashboard();
                    showSnack("已刷新底层挂载与卡槽状态");
                });
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) btnRefreshTop.getLayoutParams();
        rlp.rightMargin = M3Ui.dp(this, 8);
        topBar.addView(btnRefreshTop);

        ImageView btnDiagTop = M3Ui.createIconButton(
                this, palette, R.drawable.ic_terminal_24, "底层脱敏诊断报告", this::showDiagnosticsDialog);
        topBar.addView(btnDiagTop);
        mainCol.addView(topBar);

        // Thin M3 Indeterminate Progress Bar (3dp)
        progressBar = new IndeterminateM3Bar(this, palette);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, M3Ui.dp(this, 3)));
        progressBar.setVisibility(View.INVISIBLE);
        mainCol.addView(progressBar);

        // Main ScrollView
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scrollView.setFillViewport(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);

        LinearLayout contentCol = new LinearLayout(this);
        contentCol.setOrientation(LinearLayout.VERTICAL);
        contentCol.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 1. Hero Master Switch Banner (28dp Expressive Extra-Large Card)
        cardMasterHero = new LinearLayout(this);
        cardMasterHero.setOrientation(LinearLayout.HORIZONTAL);
        cardMasterHero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = M3Ui.dp(this, 4);
        cardMasterHero.setLayoutParams(heroLp);
        int heroPadH = M3Ui.dp(this, 20);
        int heroPadV = M3Ui.dp(this, 20);
        cardMasterHero.setPadding(heroPadH, heroPadV, heroPadH, heroPadV);
        cardMasterHero.setClickable(true);
        cardMasterHero.setFocusable(true);

        heroIconContainer = new FrameLayout(this);
        int heroCirclePx = M3Ui.dp(this, 48);
        LinearLayout.LayoutParams hicLp = new LinearLayout.LayoutParams(heroCirclePx, heroCirclePx);
        hicLp.rightMargin = M3Ui.dp(this, 16);
        heroIconContainer.setLayoutParams(hicLp);

        ivHeroIcon = new ImageView(this);
        int heroIconPx = M3Ui.dp(this, 24);
        ivHeroIcon.setLayoutParams(new FrameLayout.LayoutParams(heroIconPx, heroIconPx, Gravity.CENTER));
        ivHeroIcon.setImageResource(R.drawable.ic_power_24);
        heroIconContainer.addView(ivHeroIcon);
        cardMasterHero.addView(heroIconContainer);

        LinearLayout heroTextCol = new LinearLayout(this);
        heroTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        htLp.rightMargin = M3Ui.dp(this, 12);
        heroTextCol.setLayoutParams(htLp);

        tvHeroTitle = new TextView(this);
        tvHeroTitle.setText("正在读取桥接状态…");
        tvHeroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f);
        tvHeroTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        heroTextCol.addView(tvHeroTitle);

        tvHeroSubtitle = new TextView(this);
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsLp.topMargin = M3Ui.dp(this, 3);
        tvHeroSubtitle.setLayoutParams(hsLp);
        tvHeroSubtitle.setText("正在检测系统特权与电话框架");
        tvHeroSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f);
        tvHeroSubtitle.setLineSpacing(M3Ui.dpf(this, 2f), 1.0f);
        heroTextCol.addView(tvHeroSubtitle);
        cardMasterHero.addView(heroTextCol);

        switchMasterMode = new M3Ui.M3Switch(this, palette);
        cardMasterHero.addView(switchMasterMode);
        styleHeroCard(true, true);
        contentCol.addView(cardMasterHero);

        // 2. Conditional Alert Banner
        cardAlertBanner = new LinearLayout(this);
        cardAlertBanner.setOrientation(LinearLayout.HORIZONTAL);
        cardAlertBanner.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams alertLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alertLp.topMargin = M3Ui.dp(this, 10);
        cardAlertBanner.setLayoutParams(alertLp);
        int alertPad = M3Ui.dp(this, 16);
        cardAlertBanner.setPadding(alertPad, M3Ui.dp(this, 14), alertPad, M3Ui.dp(this, 14));
        cardAlertBanner.setBackground(M3Ui.roundedRect(this, palette.errorContainer, 20f, 0, 0));
        cardAlertBanner.setVisibility(View.GONE);

        ImageView ivAlert = new ImageView(this);
        int alertIconPx = M3Ui.dp(this, 20);
        LinearLayout.LayoutParams aiLp = new LinearLayout.LayoutParams(alertIconPx, alertIconPx);
        aiLp.rightMargin = M3Ui.dp(this, 12);
        aiLp.topMargin = M3Ui.dp(this, 2);
        ivAlert.setLayoutParams(aiLp);
        ivAlert.setImageResource(R.drawable.ic_error_24);
        ivAlert.setImageTintList(ColorStateList.valueOf(palette.onErrorContainer));
        cardAlertBanner.addView(ivAlert);

        tvAlertMessage = new TextView(this);
        tvAlertMessage.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvAlertMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f);
        tvAlertMessage.setLineSpacing(M3Ui.dpf(this, 2.5f), 1.0f);
        tvAlertMessage.setTextColor(palette.onErrorContainer);
        cardAlertBanner.addView(tvAlertMessage);
        contentCol.addView(cardAlertBanner);

        // 3. Hardware & Framework Health Section
        contentCol.addView(M3Ui.createSectionHeader(this, palette, "硬件与框架状态"));

        LinearLayout simCard = new LinearLayout(this);
        simCard.setOrientation(LinearLayout.VERTICAL);
        simCard.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int cardPad = M3Ui.dp(this, 18);
        simCard.setPadding(cardPad, cardPad, cardPad, cardPad);
        simCard.setBackground(M3Ui.roundedRect(this, palette.cardBg, 24f, 0, 0));

        // SIM2 Header Row
        LinearLayout simHead = new LinearLayout(this);
        simHead.setOrientation(LinearLayout.HORIZONTAL);
        simHead.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout simIconBox = new FrameLayout(this);
        int simBoxPx = M3Ui.dp(this, 44);
        LinearLayout.LayoutParams sibLp = new LinearLayout.LayoutParams(simBoxPx, simBoxPx);
        sibLp.rightMargin = M3Ui.dp(this, 14);
        simIconBox.setLayoutParams(sibLp);
        simIconBox.setBackground(M3Ui.roundedRect(this, palette.secondaryContainer, 14f, 0, 0));

        ImageView ivSim = new ImageView(this);
        int simIcPx = M3Ui.dp(this, 22);
        ivSim.setLayoutParams(new FrameLayout.LayoutParams(simIcPx, simIcPx, Gravity.CENTER));
        ivSim.setImageResource(R.drawable.ic_sim_card_24);
        ivSim.setImageTintList(ColorStateList.valueOf(palette.onSecondaryContainer));
        simIconBox.addView(ivSim);
        simHead.addView(simIconBox);

        LinearLayout simTitleCol = new LinearLayout(this);
        simTitleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stcLp.rightMargin = M3Ui.dp(this, 8);
        simTitleCol.setLayoutParams(stcLp);

        TextView tvSimTitle = new TextView(this);
        tvSimTitle.setText("物理卡槽 2 (SIM2)");
        tvSimTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvSimTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvSimTitle.setTextColor(palette.onSurface);
        simTitleCol.addView(tvSimTitle);

        tvSim2Meta = new TextView(this);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = M3Ui.dp(this, 2);
        tvSim2Meta.setLayoutParams(metaLp);
        tvSim2Meta.setText("小米 LPA · 正在检测…");
        tvSim2Meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSim2Meta.setTextColor(palette.onSurfaceVariant);
        simTitleCol.addView(tvSim2Meta);
        simHead.addView(simTitleCol);

        tvSim2Badge = new TextView(this);
        tvSim2Badge.setText("检测中");
        tvSim2Badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvSim2Badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvSim2Badge.setPadding(M3Ui.dp(this, 11), M3Ui.dp(this, 5), M3Ui.dp(this, 11), M3Ui.dp(this, 5));
        simHead.addView(tvSim2Badge);
        simCard.addView(simHead);

        tvSim2Detail = new TextView(this);
        LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detLp.topMargin = M3Ui.dp(this, 12);
        tvSim2Detail.setLayoutParams(detLp);
        tvSim2Detail.setText("正在通过 ISD-R 通道读取卡片状态…");
        tvSim2Detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvSim2Detail.setLineSpacing(M3Ui.dpf(this, 2.5f), 1.0f);
        tvSim2Detail.setTextColor(palette.onSurfaceVariant);
        simCard.addView(tvSim2Detail);

        // 2x2 Tonal Health Grid
        LinearLayout gridRow1 = new LinearLayout(this);
        gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gr1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gr1Lp.topMargin = M3Ui.dp(this, 14);
        gridRow1.setLayoutParams(gr1Lp);
        tvBadgeMount = addHealthTile(gridRow1, "LPA 特权挂载", true);
        tvBadgePhoneHook = addHealthTile(gridRow1, "电话框架 Hook", false);
        simCard.addView(gridRow1);

        LinearLayout gridRow2 = new LinearLayout(this);
        gridRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gr2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gr2Lp.topMargin = M3Ui.dp(this, 8);
        gridRow2.setLayoutParams(gr2Lp);
        tvBadgeLpaHook = addHealthTile(gridRow2, "小米 LPA Hook", true);
        tvBadgeEuicc = addHealthTile(gridRow2, "系统 eUICC 服务", false);
        simCard.addView(gridRow2);

        // Quick Action Buttons inside Hardware Card
        btnManageEsim = M3Ui.createActionButton(
                this,
                palette,
                M3Ui.ButtonStyle.FILLED,
                R.drawable.ic_qr_code_scanner_24,
                "打开小米原生 eSIM 管理与写卡",
                48,
                () -> {
                    try {
                        startActivity(new Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS));
                    } catch (Throwable e) {
                        showSnack("请先确认桥接服务与 LPA 系统特权挂载已生效");
                    }
                });
        LinearLayout.LayoutParams manageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        manageLp.topMargin = M3Ui.dp(this, 16);
        btnManageEsim.setLayoutParams(manageLp);
        simCard.addView(btnManageEsim);

        LinearLayout secBtnRow = new LinearLayout(this);
        secBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sbrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbrLp.topMargin = M3Ui.dp(this, 10);
        secBtnRow.setLayoutParams(sbrLp);

        btnMobileNetwork = M3Ui.createActionButton(
                this,
                palette,
                M3Ui.ButtonStyle.TONAL,
                R.drawable.ic_settings_cell_24,
                "移动网络设置",
                44,
                () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS));
                    } catch (Throwable e) {
                        showSnack("无法打开系统移动网络设置");
                    }
                });
        LinearLayout.LayoutParams mnLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mnLp.rightMargin = M3Ui.dp(this, 5);
        btnMobileNetwork.setLayoutParams(mnLp);
        secBtnRow.addView(btnMobileNetwork);

        btnLaunchTester = M3Ui.createActionButton(
                this,
                palette,
                M3Ui.ButtonStyle.TONAL,
                R.drawable.ic_science_24,
                "模拟写卡测试",
                44,
                () -> {
                    try {
                        Intent launch = getPackageManager().getLaunchIntentForPackage(ProbeInspector.TESTER_PKG);
                        if (launch != null) {
                            startActivity(launch);
                        } else {
                            showSnack("独立测试应用当前已隐藏，可在下方开启「模拟写卡测试」工具");
                        }
                    } catch (Throwable e) {
                        showSnack("无法启动模拟写卡测试工具");
                    }
                });
        LinearLayout.LayoutParams ltLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ltLp.leftMargin = M3Ui.dp(this, 5);
        btnLaunchTester.setLayoutParams(ltLp);
        secBtnRow.addView(btnLaunchTester);
        simCard.addView(secBtnRow);

        contentCol.addView(simCard);

        // 4. Core Runtime Switches (Zero-Root Instant Switches, Segmented 24dp/6dp Group)
        contentCol.addView(M3Ui.createSectionHeader(this, palette, "核心功能开关 · 免 Root 实时生效"));

        rowSystemEsim = new M3Ui.SwitchRow(
                this,
                palette,
                R.drawable.ic_sync_alt_24,
                "开启系统 eSIM（同步卡槽 2）",
                "与移动网络页 eSIM 开关实时双向同步",
                24f, 6f, 2);
        contentCol.addView(rowSystemEsim.card);

        rowCarrierBridge = new M3Ui.SwitchRow(
                this,
                palette,
                R.drawable.ic_verified_user_24,
                "运营商 App 一键写卡授权桥接",
                "允许漫游与运营商应用调起小米原生写卡确认弹窗",
                6f, 6f, 2);
        contentCol.addView(rowCarrierBridge.card);

        rowLpaUriBridge = new M3Ui.SwitchRow(
                this,
                palette,
                R.drawable.ic_link_24,
                "接管 LPA:// 激活链接与扫码动作",
                "点击 LPA:1$ 链接自动拉起小米写卡页；关闭可交由其他工具处理",
                6f, 6f, 2);
        contentCol.addView(rowLpaUriBridge.card);

        rowRewriteText = new M3Ui.SwitchRow(
                this,
                palette,
                R.drawable.ic_translate_24,
                "系统设置 eSIM 提示文案修正",
                "将移动网络页“替换 SIM 卡 2”文案修正为实体卡同步说明",
                6f, 24f, 0);
        contentCol.addView(rowRewriteText.card);

        // 5. Kernel Mount Immunity & Component Management (Root Switches, Segmented 24dp/6dp Group)
        contentCol.addView(M3Ui.createSectionHeader(this, palette, "内核兼容与组件管理 · 需 Root 授权"));

        rowUmountGuard = new M3Ui.SwitchRow(
                this,
                palette,
                R.drawable.ic_shield_24,
                "SukiSU / KernelSU 挂载卸载免疫",
                "同步 /data/app 系统更新层，彻底免疫内核 umount 导致的写卡闪退",
                24f, 6f, 2);
        contentCol.addView(rowUmountGuard.card);

        rowTesterApp = new M3Ui.SwitchRow(
                this,
                palette,
                R.drawable.ic_science_24,
                "桌面保留「模拟写卡测试」工具",
                "控制是否在桌面显示零风险一键写卡测试应用图标",
                6f, 24f, 0);
        contentCol.addView(rowTesterApp.card);

        // 6. Footer Utility Buttons
        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams frLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        frLp.topMargin = M3Ui.dp(this, 16);
        footerRow.setLayoutParams(frLp);

        LinearLayout btnReprobe = M3Ui.createActionButton(
                this,
                palette,
                M3Ui.ButtonStyle.OUTLINED,
                R.drawable.ic_refresh_24,
                "重新识别卡槽 2",
                42,
                this::triggerReprobeSim2);
        LinearLayout.LayoutParams rpLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rpLp.rightMargin = M3Ui.dp(this, 5);
        btnReprobe.setLayoutParams(rpLp);
        footerRow.addView(btnReprobe);

        LinearLayout btnShowDiag = M3Ui.createActionButton(
                this,
                palette,
                M3Ui.ButtonStyle.OUTLINED,
                R.drawable.ic_terminal_24,
                "查看诊断报告",
                42,
                this::showDiagnosticsDialog);
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sdLp.leftMargin = M3Ui.dp(this, 5);
        btnShowDiag.setLayoutParams(sdLp);
        footerRow.addView(btnShowDiag);

        contentCol.addView(footerRow);

        scrollView.addView(contentCol);
        mainCol.addView(scrollView);
        rootOverlay.addView(mainCol);

        // Floating M3 Bottom Snackbar
        snackView = new TextView(this);
        FrameLayout.LayoutParams snackLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        snackLp.leftMargin = M3Ui.dp(this, 18);
        snackLp.rightMargin = M3Ui.dp(this, 18);
        snackLp.bottomMargin = M3Ui.dp(this, 24);
        snackView.setLayoutParams(snackLp);
        snackView.setPadding(M3Ui.dp(this, 16), M3Ui.dp(this, 13), M3Ui.dp(this, 16), M3Ui.dp(this, 13));
        snackView.setBackground(M3Ui.roundedRect(this, palette.inverseSurface, 14f, 0, 0));
        snackView.setTextColor(palette.inverseOnSurface);
        snackView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        snackView.setElevation(M3Ui.dpf(this, 6f));
        snackView.setVisibility(View.GONE);
        rootOverlay.addView(snackView);

        // Edge-to-Edge System Bar Insets
        final int baseSide = M3Ui.dp(this, 16);
        final int baseBottom = M3Ui.dp(this, 28);
        final int topBarPadSide = M3Ui.dp(this, 18);
        final int topBarPadVert = M3Ui.dp(this, 12);
        rootOverlay.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            topBar.setPadding(
                    topBarPadSide + bars.left,
                    topBarPadVert + bars.top,
                    topBarPadSide + bars.right,
                    topBarPadVert);
            scrollView.setPadding(
                    baseSide + bars.left,
                    M3Ui.dp(this, 4),
                    baseSide + bars.right,
                    baseBottom + bars.bottom);
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) snackView.getLayoutParams();
            slp.bottomMargin = M3Ui.dp(this, 20) + bars.bottom;
            snackView.setLayoutParams(slp);
            return WindowInsets.CONSUMED;
        });

        return rootOverlay;
    }

    private TextView addHealthTile(LinearLayout parentRow, String label, boolean isLeft) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (isLeft) {
            lp.rightMargin = M3Ui.dp(this, 4);
        } else {
            lp.leftMargin = M3Ui.dp(this, 4);
        }
        tile.setLayoutParams(lp);
        int padH = M3Ui.dp(this, 12);
        int padV = M3Ui.dp(this, 10);
        tile.setPadding(padH, padV, padH, padV);
        tile.setBackground(M3Ui.roundedRect(this, palette.tileBg, 14f, 0, 0));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvLabel.setTextColor(palette.onSurfaceVariant);
        tile.addView(tvLabel);

        TextView tvVal = new TextView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = M3Ui.dp(this, 3);
        tvVal.setLayoutParams(vlp);
        tvVal.setText("○ 检测中");
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvVal.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvVal.setTextColor(palette.onSurface);
        tile.addView(tvVal);

        parentRow.addView(tile);
        return tvVal;
    }

    private void triggerReprobeSim2() {
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
                showSnack(ok ? "已触发卡槽 2 重新检测" : "请先启用模块并激活电话框架 Hook");
                refreshDashboard();
            });
        });
    }

    private void bindRowClick(M3Ui.SwitchRow row, String disabledHint) {
        row.card.setOnClickListener(v -> {
            if (row.toggle.isEnabled()) {
                row.toggle.performUserToggle();
            } else if (disabledHint != null) {
                showSnack(disabledHint);
            }
        });
    }

    private void setupSwitches() {
        cardMasterHero.setOnClickListener(v -> {
            if (switchMasterMode.isEnabled()) {
                switchMasterMode.performUserToggle();
            }
        });

        bindRowClick(rowSystemEsim, "需启用桥接模块且卡槽 2 识别为实体 eSIM 卡后可切换");
        bindRowClick(rowCarrierBridge, "请先启用桥接服务并确认电话框架 Hook 已生效");
        bindRowClick(rowLpaUriBridge, null);
        bindRowClick(rowRewriteText, "请先启用桥接服务并确认电话框架 Hook 已生效");
        bindRowClick(rowUmountGuard, "需 LPA 系统特权挂载生效后方可切换双层驻留保护");
        bindRowClick(rowTesterApp, "请先开启上方主桥接服务开关");

        switchMasterMode.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            runRootSwitchAction(
                    switchMasterMode,
                    null,
                    isChecked,
                    new String[]{isChecked ? "on" : "off"},
                    output -> {
                        if (output.contains("RESULT=MISSING_METAMODULE")) {
                            showInfoDialog(
                                    "缺少系统挂载元模块",
                                    "检测到当前 KernelSU / SukiSU 环境尚未启用系统挂载元模块。\n\n"
                                            + "请先安装并启用 Hybrid Mount 或 meta-overlayfs（澎湃 OS 4 原厂多层 Overlay 分区不建议使用 Magic Mount-rs），重启手机后再开启本开关。");
                        } else if (output.contains("RESULT=NEED_REBOOT")) {
                            showInfoDialog(
                                    "已切换为开启（需重启生效）",
                                    "模块配置已设为开启状态。请重启手机，由系统挂载小米原生 LPA 特权声明后即可使用。");
                        } else if (output.contains("RESULT=ACTIVE_NOW")) {
                            showSnack("实体 eSIM 桥接服务已即时开启");
                        } else if (output.contains("RESULT=OFF_APPLIED")) {
                            showInfoDialog(
                                    "已关闭桥接服务",
                                    "已还原系统设置与卡槽 2 状态并清理更新层。重启手机后彻底卸载系统底层能力声明。");
                        } else {
                            showSnack(isChecked ? "已执行开启指令" : "已执行关闭指令");
                        }
                        refreshDashboard();
                    });
        });

        rowSystemEsim.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowSystemEsim.refreshIconTint(isChecked);
            setBusy(true);
            rowSystemEsim.toggle.setEnabled(false);
            IO_EXEC.execute(() -> {
                Bundle extras = new Bundle();
                extras.putBoolean("enabled", isChecked);
                Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "set_esim_state", extras);
                boolean ok = res != null && res.getBoolean("ok", false);
                runOnUiThread(() -> {
                    setBusy(false);
                    rowSystemEsim.toggle.setEnabled(true);
                    if (!ok) {
                        setSwitchSilently(rowSystemEsim.toggle, rowSystemEsim, !isChecked);
                        showSnack("切换未成功：请确认卡槽 2 已插入实体 eSIM 卡且电话 Hook 正常");
                    } else {
                        showSnack(isChecked ? "系统 eSIM 与卡槽 2 已同步开启" : "系统 eSIM 与卡槽 2 已同步停用");
                        refreshDashboard();
                    }
                });
            });
        });

        rowCarrierBridge.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowCarrierBridge.refreshIconTint(isChecked);
            updatePhonePref(rowCarrierBridge, CardTypeGuard.KEY_CARRIER_BRIDGE, isChecked,
                    isChecked ? "已开启第三方运营商一键写卡授权桥接" : "已关闭第三方运营商一键写卡授权桥接");
        });

        rowLpaUriBridge.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowLpaUriBridge.refreshIconTint(isChecked);
            try {
                ComponentName comp = new ComponentName(this, LpaBridgeActivity.class);
                getPackageManager().setComponentEnabledSetting(
                        comp,
                        isChecked
                                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (Throwable e) {
                setSwitchSilently(rowLpaUriBridge.toggle, rowLpaUriBridge, !isChecked);
                showSnack("组件状态修改失败：" + e.getClass().getSimpleName());
                return;
            }
            IO_EXEC.execute(() -> {
                Bundle extras = new Bundle();
                extras.putString("key", CardTypeGuard.KEY_LPA_URI_BRIDGE);
                extras.putBoolean("enabled", isChecked);
                ProbeInspector.callPhoneCtrl(MainActivity.this, "set_pref", extras);
                runOnUiThread(() -> {
                    showSnack(isChecked
                            ? "已开启 LPA:// 激活码链接与扫码动作接管"
                            : "已释放 LPA:// 链接接管（可交由其他写卡工具处理）");
                    refreshDashboard();
                });
            });
        });

        rowRewriteText.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowRewriteText.refreshIconTint(isChecked);
            updatePhonePref(rowRewriteText, CardTypeGuard.KEY_REWRITE_TEXT, isChecked,
                    isChecked ? "已开启系统设置提示文案修正" : "已恢复系统原始 eSIM 提示文案");
        });

        rowUmountGuard.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowUmountGuard.refreshIconTint(isChecked);
            runRootSwitchAction(
                    rowUmountGuard.toggle,
                    rowUmountGuard,
                    isChecked,
                    new String[]{"umount_guard", isChecked ? "on" : "off"},
                    output -> {
                        showSnack(isChecked
                                ? "已开启内核 umount 卸载免疫（系统更新层已同步）"
                                : "已剥离 /data/app 更新层，回退至纯 Overlay 挂载");
                        refreshDashboard();
                    });
        });

        rowTesterApp.toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            if (bindingUi) return;
            rowTesterApp.refreshIconTint(isChecked);
            runRootSwitchAction(
                    rowTesterApp.toggle,
                    rowTesterApp,
                    isChecked,
                    new String[]{"tester", isChecked ? "on" : "off"},
                    output -> {
                        showSnack(isChecked
                                ? "已在桌面恢复安装「eSIM 模拟写卡测试」工具"
                                : "已卸载隐藏独立测试工具图标");
                        refreshDashboard();
                    });
        });
    }

    private void updatePhonePref(M3Ui.SwitchRow row, String key, boolean isChecked, String okMsg) {
        IO_EXEC.execute(() -> {
            Bundle extras = new Bundle();
            extras.putString("key", key);
            extras.putBoolean("enabled", isChecked);
            Bundle res = ProbeInspector.callPhoneCtrl(MainActivity.this, "set_pref", extras);
            boolean ok = res != null && res.getBoolean("ok", false);
            runOnUiThread(() -> {
                if (!ok) {
                    setSwitchSilently(row.toggle, row, !isChecked);
                    showSnack("无法保存设置：请确认模块已开启且 LSPosed 电话框架作用域已生效");
                } else {
                    showSnack(okMsg);
                    refreshDashboard();
                }
            });
        });
    }

    private interface ActionCallback {
        void onSuccess(String stdout);
    }

    private void runRootSwitchAction(
            M3Ui.M3Switch sw,
            M3Ui.SwitchRow ownerRow,
            boolean desiredState,
            String[] args,
            ActionCallback callback) {
        setBusy(true);
        sw.setEnabled(false);
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
                sw.setEnabled(true);
                if (!success) {
                    setSwitchSilently(sw, ownerRow, !desiredState);
                    showRootPermissionDialog();
                } else {
                    callback.onSuccess(finalOut);
                }
            });
        });
    }

    private void showRootPermissionDialog() {
        M3Ui.showM3Dialog(
                this,
                palette,
                "需要授予 Root 控制权限",
                "修改底层模块总开关或内核挂载策略需要 Root 权限。\n\n"
                        + "请在 KernelSU / SukiSU Ultra / Magisk / APatch 的「超级用户」列表中为「HYPEROS4实体ESIM桥接」开启 Root 授权后再操作。\n\n"
                        + "（注：上方 4 项日常核心功能开关无需 Root 授权即可实时生效。）",
                false,
                null,
                null,
                "我知道了");
    }

    private void showInfoDialog(String title, String message) {
        M3Ui.showM3Dialog(this, palette, title, message, false, null, null, "确定");
    }

    private void showDiagnosticsDialog() {
        String pretty;
        try {
            JSONObject obj = lastReport != null
                    ? lastReport
                    : new JSONObject(ProbeInspector.inspect(this));
            pretty = obj.toString(2);
        } catch (Throwable e) {
            pretty = "诊断数据读取失败：" + e.getMessage();
        }
        final String reportText = pretty;
        M3Ui.showM3Dialog(
                this,
                palette,
                "底层脱敏诊断报告",
                reportText,
                true,
                "复制报告",
                () -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("EsimH4Diagnostics", reportText));
                        showSnack("脱敏诊断报告已复制到剪贴板");
                    }
                },
                "关闭");
    }

    private void setBusy(boolean busy) {
        if (progressBar == null) return;
        if (busy) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.startAnimation();
        } else {
            progressBar.stopAnimation();
            progressBar.setVisibility(View.INVISIBLE);
        }
    }

    private void setSwitchSilently(M3Ui.M3Switch sw, M3Ui.SwitchRow ownerRow, boolean checked) {
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

    private void showSnack(String msg) {
        if (snackView == null || isFinishing() || isDestroyed()) return;
        if (hideSnackTask != null) {
            uiHandler.removeCallbacks(hideSnackTask);
        }
        snackView.setText(msg);
        if (snackView.getVisibility() != View.VISIBLE) {
            snackView.setAlpha(0f);
            snackView.setTranslationY(M3Ui.dpf(this, 14f));
            snackView.setVisibility(View.VISIBLE);
        }
        snackView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(M3Ui.M3_STANDARD_EASING)
                .start();
        hideSnackTask = () -> snackView.animate()
                .alpha(0f)
                .translationY(M3Ui.dpf(this, 10f))
                .setDuration(160L)
                .withEndAction(() -> snackView.setVisibility(View.GONE))
                .start();
        uiHandler.postDelayed(hideSnackTask, 2600L);
    }

    private void refreshDashboard() {
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
                });
            }
        });
    }

    private void bindDashboard(JSONObject r) {
        bindingUi = true;
        try {
            boolean moduleActive = r.optBoolean("moduleActive", false);
            boolean lpaPrivMounted = r.optBoolean("lpaPrivMounted", false);
            boolean lpaUpdatedSys = r.optBoolean("lpaUpdatedSystemApp", false);
            boolean phoneHook = r.optBoolean("phoneHookActive", false);
            boolean lpaHook = r.optBoolean("lpaHookActive", false);
            boolean hasFeature = r.optBoolean("feature", false);
            boolean euiccEnabled = r.optBoolean("enabled", false);
            boolean testerInstalled = r.optBoolean("testerInstalled", false);

            int cardType = r.optInt("cardType", -1);
            int esimSwitch = r.optInt("esimSwitch", -1);
            int sim2State = r.optInt("sim2SimState", TelephonyManager.SIM_STATE_UNKNOWN);
            int defaultCardId = r.optInt("defaultCardId", -1);
            boolean hasActiveSub = r.optBoolean("sim2HasActiveSub", false);
            String carrierName = r.optString("sim2CarrierName", "");
            String lpaVer = r.optString("lpaVersion", "未安装");
            boolean realEid = r.optBoolean("realEidPresent", false);

            boolean carrierBridge = r.optBoolean("carrierBridgeEnabled", true);
            boolean lpaUriBridge = r.optBoolean("lpaUriBridgeEnabled", true);
            boolean rewriteText = r.optBoolean("rewriteTextEnabled", true);

            // 1. Bind Master Hero Banner
            switchMasterMode.setChecked(moduleActive, false);
            styleHeroCard(moduleActive, lpaPrivMounted && phoneHook);
            if (!moduleActive) {
                tvHeroTitle.setText("实体 eSIM 桥接已关闭");
                tvHeroSubtitle.setText("点击右侧开关启用底层桥接服务");
            } else if (!lpaPrivMounted) {
                tvHeroTitle.setText("桥接已开启 · 等待系统挂载");
                tvHeroSubtitle.setText("小米 LPA 特权目录尚未挂载生效，请查看下方提示");
            } else if (!phoneHook) {
                tvHeroTitle.setText("桥接已开启 · 等待 LSPosed 激活");
                tvHeroSubtitle.setText("请在 LSPosed 中确认勾选电话服务与小米 eSIM 并重启");
            } else {
                tvHeroTitle.setText("实体 eSIM 桥接运行中");
                tvHeroSubtitle.setText(cardType == 1
                        ? "底层特权与双通道通信已就绪 · 卡槽 2 已接管"
                        : "底层特权与框架已就绪 · 等待卡槽 2 插入实体 eSIM");
            }

            // 2. Bind Conditional Actionable Alert Banner
            if (moduleActive && !lpaPrivMounted) {
                cardAlertBanner.setVisibility(View.VISIBLE);
                tvAlertMessage.setText("LPA 系统特权挂载未生效：KernelSU / SukiSU 用户请确认已安装并启用 Hybrid Mount 或 meta-overlayfs 元模块（澎湃 OS 4 原厂多层 Overlay 分区不建议使用 Magic Mount-rs），重启手机后生效。");
            } else if (moduleActive && !phoneHook) {
                cardAlertBanner.setVisibility(View.VISIBLE);
                tvAlertMessage.setText("未检测到电话框架 Hook 响应：请在 LSPosed 管理器中启用「HYPEROS4实体ESIM桥接」，勾选作用域 com.android.phone 与 com.miui.euicc 后重启手机。");
            } else {
                cardAlertBanner.setVisibility(View.GONE);
            }

            // 3. Bind SIM2 Hardware & Framework Overview Card
            String eidTag = realEid ? "EID 已校验" : (cardType == 1 ? "ISD-R 已校验" : "待识别卡片");
            tvSim2Meta.setText("小米 LPA " + lpaVer
                    + (defaultCardId >= 0 ? " · 卡槽 ID: " + defaultCardId : "")
                    + " · " + eidTag);

            bindSim2Status(cardType, esimSwitch, sim2State, hasActiveSub, carrierName, lpaPrivMounted, moduleActive);

            setHealthTile(tvBadgeMount, lpaPrivMounted, lpaPrivMounted ? "已生效" : "未挂载", !moduleActive);
            setHealthTile(tvBadgePhoneHook, phoneHook, phoneHook ? "已激活" : "未生效", false);
            setHealthTile(tvBadgeLpaHook, lpaHook, lpaHook ? "已激活" : (lpaPrivMounted ? "待检查" : "未挂载"), !moduleActive);
            setHealthTile(tvBadgeEuicc, hasFeature && euiccEnabled,
                    euiccEnabled ? "已启用" : (hasFeature ? "待启用" : "未声明"), !moduleActive);

            M3Ui.setButtonEnabled(btnManageEsim, moduleActive && lpaPrivMounted && cardType != 0);
            btnLaunchTester.setAlpha(testerInstalled ? 1.0f : 0.65f);

            // 4. Bind Core Runtime Switches
            boolean canToggleEsim = moduleActive && lpaPrivMounted && phoneHook && cardType == 1;
            String subEsim;
            if (!moduleActive) {
                subEsim = "桥接服务关闭中 · 开启模块后可同步控制卡槽 2";
            } else if (cardType == 1) {
                subEsim = (esimSwitch == 1)
                        ? "已开启 · 与系统移动网络 eSIM 开关实时双向同步"
                        : "已关闭 · 点击立即唤醒激活卡槽 2 中的实体 eSIM 卡";
            } else if (cardType == 0) {
                subEsim = "安全隔离生效 · 卡槽 2 未插卡或为普通物理 SIM 卡";
            } else {
                subEsim = "正在识别卡槽 2 卡片类型，请稍候…";
            }
            rowSystemEsim.setRowState(cardType == 1 && esimSwitch == 1, canToggleEsim, subEsim);

            boolean canUpdatePhonePrefs = moduleActive && phoneHook;
            rowCarrierBridge.setRowState(
                    carrierBridge,
                    canUpdatePhonePrefs,
                    carrierBridge
                            ? "已开启 · 允许漫游与运营商 App 调起小米系统写卡确认弹窗"
                            : "已关闭 · 仅具有运营商特权签名的官方应用可直接写卡");

            rowLpaUriBridge.setRowState(
                    lpaUriBridge,
                    true,
                    lpaUriBridge
                            ? "已开启 · 点击 LPA:1$ 激活链接自动拉起小米原生写卡页"
                            : "已关闭 · 不拦截 LPA:// 链接，可交由 EasyLPAC 等工具打开");

            rowRewriteText.setRowState(
                    rewriteText,
                    canUpdatePhonePrefs,
                    rewriteText
                            ? "已开启 · 将移动网络页“替换 SIM 卡 2”修正为实体卡同步说明"
                            : "已关闭 · 显示澎湃 OS 原厂默认 eSIM 开关提示文案");

            // 5. Bind Root / Component Switches
            String subUmount;
            if (lpaUpdatedSys) {
                subUmount = "已生效 · 系统特权 + /data/app 双层驻留，彻底免疫内核 umount 闪退";
            } else if (lpaPrivMounted) {
                subUmount = "当前为纯 Overlay 挂载 · 开启后同步更新层以免疫 SukiSU 默认卸载";
            } else {
                subUmount = "等待 LPA 特权挂载生效后自动同步系统更新层";
            }
            rowUmountGuard.setRowState(lpaUpdatedSys, moduleActive && lpaPrivMounted, subUmount);

            rowTesterApp.setRowState(
                    testerInstalled,
                    moduleActive,
                    testerInstalled
                            ? "已在桌面保留「eSIM 模拟写卡测试」图标，可随时验证写卡链路"
                            : "已隐藏独立测试工具图标，开启后自动恢复安装");
        } finally {
            bindingUi = false;
        }
    }

    private void styleHeroCard(boolean moduleActive, boolean allHealthy) {
        int bg;
        int fg;
        int circleBg;
        if (!moduleActive) {
            bg = palette.surfaceHigh;
            fg = palette.onSurface;
            circleBg = palette.tileBg;
        } else if (!allHealthy) {
            bg = palette.errorContainer;
            fg = palette.onErrorContainer;
            circleBg = M3Ui.withAlpha(fg, 0x1E);
        } else {
            bg = palette.primaryContainer;
            fg = palette.onPrimaryContainer;
            circleBg = M3Ui.withAlpha(fg, 0x1A);
        }
        cardMasterHero.setBackground(M3Ui.rippleSegmented(
                this, bg, M3Ui.withAlpha(fg, 0x1F), 28f, 28f, 0, 0));
        tvHeroTitle.setTextColor(fg);
        tvHeroSubtitle.setTextColor(M3Ui.withAlpha(fg, 0xDB));
        ivHeroIcon.setImageTintList(ColorStateList.valueOf(fg));
        heroIconContainer.setBackground(M3Ui.oval(circleBg));
    }

    private void setHealthTile(TextView tv, boolean ok, String text, boolean muted) {
        tv.setText((ok ? "● " : "○ ") + text);
        int colorRes;
        if (ok) {
            colorRes = R.color.status_ok_fg;
        } else if (muted) {
            colorRes = R.color.status_off_fg;
        } else {
            colorRes = R.color.status_err_fg;
        }
        tv.setTextColor(getColor(colorRes));
    }

    private void bindSim2Status(
            int cardType,
            int esimSwitch,
            int sim2State,
            boolean hasActiveSub,
            String carrierName,
            boolean lpaPrivMounted,
            boolean moduleActive) {
        int pillBgRes;
        int pillFgRes;
        String badge;
        String detail;

        if (!moduleActive) {
            badge = "未启用";
            pillBgRes = R.color.status_off_bg;
            pillFgRes = R.color.status_off_fg;
            detail = "桥接模块当前处于关闭状态，开启后将自动检测卡槽 2 中的可插拔实体 eSIM 卡。";
        } else if (cardType == 1) {
            boolean profileReady = hasActiveSub
                    || sim2State == TelephonyManager.SIM_STATE_READY
                    || sim2State == 10;
            if (profileReady && esimSwitch != 0) {
                badge = "套餐已启用";
                pillBgRes = R.color.status_ok_bg;
                pillFgRes = R.color.status_ok_fg;
                String carrierSuffix = (carrierName != null && !carrierName.isEmpty())
                        ? "（" + carrierName + " · 驻网就绪）"
                        : "（套餐已启用就绪）";
                detail = "已识别可插拔实体 eSIM 卡" + carrierSuffix + "，可直接管理套餐或扫码新增配置。";
            } else if (esimSwitch == 0) {
                badge = "已停用";
                pillBgRes = R.color.status_warn_bg;
                pillFgRes = R.color.status_warn_fg;
                detail = "已识别实体 eSIM 卡，当前系统 eSIM 开关已关闭（可在下方一键开启唤醒）。";
            } else {
                badge = "空白/待启用";
                pillBgRes = R.color.status_ok_bg;
                pillFgRes = R.color.status_ok_fg;
                detail = "已识别实体 eSIM 卡（零套餐空白卡或暂无启用配置），可直接点击下方「打开小米原生 eSIM 管理与写卡」下载首个套餐。";
            }
        } else if (cardType == 0) {
            if (sim2State == TelephonyManager.SIM_STATE_ABSENT) {
                badge = "未插卡";
                pillBgRes = R.color.status_off_bg;
                pillFgRes = R.color.status_off_fg;
                detail = "物理卡槽 2 当前未插入卡片，请将 ESTKme / 小白卡 / 9eSIM 等可插拔 eSIM 卡放入卡槽 2。";
            } else {
                badge = "普通 SIM 保护";
                pillBgRes = R.color.status_warn_bg;
                pillFgRes = R.color.status_warn_fg;
                detail = "卡槽 2 当前为普通物理 SIM 卡（Fail-Closed 硬件隔离保护已生效，不会干扰普通卡通信）。";
            }
        } else if (!lpaPrivMounted) {
            badge = "待挂载";
            pillBgRes = R.color.status_err_bg;
            pillFgRes = R.color.status_err_fg;
            detail = "等待小米 LPA 系统特权目录挂载生效后再完成卡片校验。";
        } else {
            badge = "识别中";
            pillBgRes = R.color.status_off_bg;
            pillFgRes = R.color.status_off_fg;
            detail = "正在通过 ISD-R 安全通道校验卡槽 2 卡片类型…";
        }

        tvSim2Badge.setText(badge);
        tvSim2Badge.setTextColor(getColor(pillFgRes));
        tvSim2Badge.setBackground(M3Ui.roundedRect(this, getColor(pillBgRes), 999f, 0, 0));
        tvSim2Detail.setText(detail);
    }

    /**
     * Sleek 3dp Material Design 3 indeterminate progress track.
     */
    private static final class IndeterminateM3Bar extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator anim;
        private float phase = 0f;

        IndeterminateM3Bar(Context ctx, M3Ui.Palette p) {
            super(ctx);
            trackPaint.setColor(p.secondaryContainer);
            barPaint.setColor(p.primary);
        }

        void startAnimation() {
            if (anim != null && anim.isRunning()) return;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1050L);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stopAnimation() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, trackPaint);

            float segW = w * 0.36f;
            float left = -segW + (w + segW) * phase;
            rect.set(left, 0, left + segW, h);
            canvas.drawRoundRect(rect, r, r, barPaint);
        }
    }

    public static String inspect(Context ctx) {
        return ProbeInspector.inspect(ctx);
    }

    public static void main(String[] args) throws Exception {
        ProbeInspector.main(args);
    }
}
