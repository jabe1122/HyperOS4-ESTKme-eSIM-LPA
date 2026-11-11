package io.github.local.esimh4tester;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pure OS-native Material Design 3 Expressive mock carrier app to verify EuiccManager
 * one-click eSIM installation, LPA:// URI bridging, and authorization dialog safely.
 */
public final class TesterActivity extends Activity {
    private static final String TAG = "EsimH4Tester";
    private static final String MODULE_VERSION = "v0.6.0";
    private static final String LPA_PKG = "com.miui.euicc";
    private static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    private static final String KEY_ENABLE_ESIM = "is_enable_esim_for_user";
    private static final String PERM_BIND_EUICC = "android.permission.BIND_EUICC_SERVICE";
    private static final String PERM_SE_PRIVILEGED = "android.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION";
    private static final String ACTION_DOWNLOAD_CB = "io.github.local.esimh4tester.ACTION_DOWNLOAD_CB";
    private static final String DEFAULT_MOCK_CODE = "LPA:1$test.esim.local$MOCK-TEST-001";
    private static final int REQ_RESOLVE = 1001;
    private static final int REQ_ACTIVATE = 1002;
    private static final java.lang.reflect.Method GET_DEFAULT_CARD_ID;

    static {
        java.lang.reflect.Method m = null;
        try {
            m = TelephonyManager.class.getMethod("getCardIdForDefaultEuicc");
        } catch (Throwable ignored) {
        }
        GET_DEFAULT_CARD_ID = m;
    }

    private TextView statusView;
    private EditText codeInput;
    private TextView logView;
    private final StringBuilder logBuffer = new StringBuilder();
    private int requestSeq = 1;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_DOWNLOAD_CB.equals(intent.getAction())) return;
            int resultCode = getResultCode();
            String phase = intent.getStringExtra("phase");
            int detailed = intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, 0);
            boolean mockIntercepted = intent.getBooleanExtra("io.github.local.esimh4.MOCK_INTERCEPTED", false);
            Log.i(TAG, "Callback received: phase=" + phase + ", resultCode=" + resultCode
                    + ", detailed=" + detailed + ", mock=" + mockIntercepted);

            if ("initial".equals(phase)) {
                if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
                    appendLog("收到系统回调: RESOLVABLE_ERROR (1)");
                    appendLog("说明: 系统已识别写卡请求，正在弹出小米原生写卡授权窗口...");
                    try {
                        EuiccManager em = getSystemService(EuiccManager.class);
                        Intent cb = new Intent(ACTION_DOWNLOAD_CB)
                                .setPackage(getPackageName())
                                .putExtra("phase", "resolved");
                        PendingIntent pi = PendingIntent.getBroadcast(
                                TesterActivity.this,
                                ++requestSeq,
                                cb,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                        );
                        em.startResolutionActivity(TesterActivity.this, REQ_RESOLVE, intent, pi);
                    } catch (Throwable t) {
                        appendLog("调起授权窗口失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                } else if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                    appendLog("直接写卡完成: RESULT_OK (0)");
                } else {
                    appendLog("写卡请求返回错误: resultCode=" + resultCode + ", detailedCode=" + detailed);
                }
            } else {
                if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK) {
                    if (mockIntercepted) {
                        appendLog("授权后回调: RESULT_OK (0)");
                        appendLog("【安全保护】检测到 test.esim.local 模拟码，已在底层拦截，未向实体卡写入测试数据！");
                    } else {
                        appendLog("授权后回调: 真实激活码写卡成功 (RESULT_OK)");
                    }
                } else {
                    appendLog("授权后回调结束: resultCode=" + resultCode + " (已取消或中止，未修改卡内数据)");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int nightFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isNight = (nightFlags == Configuration.UI_MODE_NIGHT_YES);

        registerReceiver(downloadReceiver, new IntentFilter(ACTION_DOWNLOAD_CB), Context.RECEIVER_EXPORTED);
        View root = buildM3View(isNight);
        setContentView(root);

        Window window = getWindow();
        if (window != null) {
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                window.setNavigationBarContrastEnforced(false);
            }
            View decor = window.getDecorView();
            WindowInsetsController ctrl = decor != null ? decor.getWindowInsetsController() : null;
            if (ctrl != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(isNight ? 0 : mask, mask);
            }
        }

        refreshStatus();
        appendLog("就绪（" + MODULE_VERSION + "）。点击上方「测试 1」验证运营商一键写卡授权弹窗。");

        if (getIntent() != null && getIntent().getBooleanExtra("auto_test", false)) {
            root.postDelayed(this::startMockDownload, 400);
        }
    }

    private View buildM3View(boolean isNight) {
        int pageBg = isNight
                ? sysColor("system_surface_container_lowest_dark", 0xFF0C0E13)
                : sysColor("system_surface_container_low_light", 0xFFF3F4F9);
        int cardBg = isNight
                ? sysColor("system_surface_container_dark", 0xFF1B1F26)
                : sysColor("system_surface_container_lowest_light", 0xFFFFFFFF);
        int tileBg = isNight
                ? sysColor("system_surface_container_high_dark", 0xFF252A32)
                : sysColor("system_surface_container_light", 0xFFECEEF4);
        int primary = isNight
                ? sysColor("system_primary_dark", 0xFFA5C8FF)
                : sysColor("system_primary_light", 0xFF1B5E9E);
        int onPrimary = isNight
                ? sysColor("system_on_primary_dark", 0xFF00315E)
                : sysColor("system_on_primary_light", 0xFFFFFFFF);
        int secContainer = isNight
                ? sysColor("system_secondary_container_dark", 0xFF3B4858)
                : sysColor("system_secondary_container_light", 0xFFD7E3F8);
        int onSecContainer = isNight
                ? sysColor("system_on_secondary_container_dark", 0xFFD7E3F8)
                : sysColor("system_on_secondary_container_light", 0xFF101C2B);
        int onSurface = isNight
                ? sysColor("system_on_surface_dark", 0xFFE1E2E8)
                : sysColor("system_on_surface_light", 0xFF191C20);
        int onSurfaceVar = isNight
                ? sysColor("system_on_surface_variant_dark", 0xFFC3C6CF)
                : sysColor("system_on_surface_variant_light", 0xFF43474E);
        int outlineVar = isNight
                ? sysColor("system_outline_variant_dark", 0xFF43474E)
                : sysColor("system_outline_variant_light", 0xFFC3C6CF);
        int ripple = (primary & 0x00FFFFFF) | 0x24000000;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageBg);

        // Top App Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int topH = dp(18);
        int topV = dp(12);
        topBar.setPadding(topH, topV, topH, topV);

        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams twLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        twLp.rightMargin = dp(10);
        titleWrap.setLayoutParams(twLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("eSIM 模拟写卡测试");
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(onSurface);
        titleWrap.addView(tvTitle);

        TextView tvVer = new TextView(this);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(3);
        tvVer.setLayoutParams(vlp);
        tvVer.setText(MODULE_VERSION + " · 安全拦截验证");
        tvVer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvVer.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvVer.setTextColor(onSecContainer);
        tvVer.setPadding(dp(8), dp(2), dp(8), dp(2));
        tvVer.setBackground(rounded(secContainer, 999f, 0, 0));
        titleWrap.addView(tvVer);
        topBar.addView(titleWrap);

        TextView btnRefresh = createPillButton("刷新状态", secContainer, onSecContainer, ripple, 0, 0, 38, this::refreshStatus);
        btnRefresh.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topBar.addView(btnRefresh);
        root.addView(topBar);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Card 1: Live eUICC Status Card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        statusCard.setPadding(pad, pad, pad, pad);
        statusCard.setBackground(rounded(cardBg, 24f, 0, 0));

        TextView tvStatusHdr = new TextView(this);
        tvStatusHdr.setText("当前系统 eUICC 链路状态");
        tvStatusHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tvStatusHdr.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvStatusHdr.setTextColor(onSurface);
        statusCard.addView(tvStatusHdr);

        statusView = new TextView(this);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.topMargin = dp(10);
        statusView.setLayoutParams(svLp);
        int tilePad = dp(14);
        statusView.setPadding(tilePad, dp(12), tilePad, dp(12));
        statusView.setBackground(rounded(tileBg, 16f, 0, 0));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        statusView.setLineSpacing(dp(4), 1.0f);
        statusView.setTextColor(onSurface);
        statusCard.addView(statusView);
        col.addView(statusCard);

        // Card 2: Mock Carrier Actions Card
        LinearLayout testCard = new LinearLayout(this);
        testCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tcLp.topMargin = dp(14);
        testCard.setLayoutParams(tcLp);
        testCard.setPadding(pad, pad, pad, pad);
        testCard.setBackground(rounded(cardBg, 24f, 0, 0));

        LinearLayout hdrRow = new LinearLayout(this);
        hdrRow.setOrientation(LinearLayout.HORIZONTAL);
        hdrRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTestHdr = new TextView(this);
        tvTestHdr.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvTestHdr.setText("模拟写卡验证（零写卡风险）");
        tvTestHdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tvTestHdr.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTestHdr.setTextColor(onSurface);
        hdrRow.addView(tvTestHdr);

        TextView btnReset = createPillButton("恢复默认码", tileBg, onSurfaceVar, ripple, 0, 0, 32, () -> {
            codeInput.setText(DEFAULT_MOCK_CODE);
            appendLog("已恢复默认安全测试激活码: " + DEFAULT_MOCK_CODE);
        });
        btnReset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hdrRow.addView(btnReset);
        testCard.addView(hdrRow);

        TextView tvSafety = new TextView(this);
        LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sfLp.topMargin = dp(6);
        tvSafety.setLayoutParams(sfLp);
        tvSafety.setText("包含 test.esim.local 的模拟激活码会在授权确认后由底层自动安全拦截，绝不向实体 eSIM 卡发送真实写卡 APDU。");
        tvSafety.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvSafety.setLineSpacing(dp(2), 1.0f);
        tvSafety.setTextColor(onSurfaceVar);
        testCard.addView(tvSafety);

        codeInput = new EditText(this);
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ciLp.topMargin = dp(12);
        codeInput.setLayoutParams(ciLp);
        codeInput.setText(DEFAULT_MOCK_CODE);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setSingleLine(true);
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        codeInput.setTextColor(onSurface);
        codeInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        codeInput.setBackground(rounded(tileBg, 14f, Math.max(1, dp(1)), outlineVar));
        testCard.addView(codeInput);

        TextView btnTest1 = createPillButton(
                "测试 1：运营商一键写卡授权弹窗",
                primary,
                onPrimary,
                (onPrimary & 0x00FFFFFF) | 0x26000000,
                0,
                0,
                48,
                this::startMockDownload);
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b1Lp.topMargin = dp(14);
        btnTest1.setLayoutParams(b1Lp);
        testCard.addView(btnTest1);

        TextView btnTest2 = createPillButton(
                "测试 2：LPA:// 激活链接拉起写卡页",
                secContainer,
                onSecContainer,
                ripple,
                0,
                0,
                44,
                this::startLpaUriTest);
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b2Lp.topMargin = dp(10);
        btnTest2.setLayoutParams(b2Lp);
        testCard.addView(btnTest2);

        TextView btnTest3 = createPillButton(
                "测试 3：标准激活扫码页跳转",
                Color.TRANSPARENT,
                primary,
                ripple,
                Math.max(1, dp(1)),
                outlineVar,
                44,
                this::startStandardActivationTest);
        LinearLayout.LayoutParams b3Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b3Lp.topMargin = dp(10);
        btnTest3.setLayoutParams(b3Lp);
        testCard.addView(btnTest3);

        col.addView(testCard);

        // Card 3: Real-time Callback Console
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lcLp.topMargin = dp(14);
        logCard.setLayoutParams(lcLp);
        logCard.setPadding(pad, pad, pad, pad);
        logCard.setBackground(rounded(cardBg, 24f, 0, 0));

        LinearLayout logHdr = new LinearLayout(this);
        logHdr.setOrientation(LinearLayout.HORIZONTAL);
        logHdr.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLogTitle = new TextView(this);
        tvLogTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvLogTitle.setText("实时回调控制台");
        tvLogTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tvLogTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvLogTitle.setTextColor(onSurface);
        logHdr.addView(tvLogTitle);

        TextView btnClear = createPillButton("清空日志", tileBg, onSurfaceVar, ripple, 0, 0, 32, () -> {
            logBuffer.setLength(0);
            logView.setText("日志已清空。\n");
        });
        btnClear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        logHdr.addView(btnClear);
        logCard.addView(logHdr);

        logView = new TextView(this);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lvLp.topMargin = dp(10);
        logView.setLayoutParams(lvLp);
        logView.setMinimumHeight(dp(120));
        logView.setPadding(dp(14), dp(12), dp(14), dp(12));
        logView.setBackground(rounded(0xFF12151C, 16f, 0, 0));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        logView.setLineSpacing(dp(3), 1.0f);
        logView.setTextColor(0xFFD8E4F8);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);

        col.addView(logCard);
        scroll.addView(col);
        root.addView(scroll);

        final int sidePad = dp(16);
        final int botPad = dp(28);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            topBar.setPadding(topH + bars.left, topV + bars.top, topH + bars.right, topV);
            scroll.setPadding(sidePad + bars.left, dp(4), sidePad + bars.right, botPad + bars.bottom);
            return WindowInsets.CONSUMED;
        });

        return root;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int sysColor(String name, int fallback) {
        if (Build.VERSION.SDK_INT < 34) return fallback;
        try {
            Resources res = getResources();
            int id = res.getIdentifier(name, "color", "android");
            if (id != 0) {
                return getColor(id);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private GradientDrawable rounded(int fill, float radiusDp, int strokePx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        gd.setColor(fill);
        if (strokePx > 0) {
            gd.setStroke(strokePx, strokeColor);
        }
        return gd;
    }

    private TextView createPillButton(
            String label,
            int bg,
            int fg,
            int rippleColor,
            int strokePx,
            int strokeColor,
            int minHeightDp,
            Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setMinimumHeight(dp(minHeightDp));
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(fg);
        float radius = minHeightDp / 2f;
        Drawable content = rounded(bg, radius, strokePx, strokeColor);
        Drawable mask = rounded(Color.WHITE, radius, 0, 0);
        tv.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask));
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> action.run());
        return tv;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(downloadReceiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESOLVE) {
            appendLog("系统授权窗口返回: resultCode=" + (resultCode == Activity.RESULT_OK
                    ? "RESULT_OK (-1，用户点击确定)"
                    : "RESULT_CANCELED (0，用户取消)"));
        } else if (requestCode == REQ_ACTIVATE) {
            appendLog("系统扫码写卡页返回: resultCode=" + resultCode);
        }
    }

    private boolean isLpaPrivMounted(PackageManager pm) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(LPA_PKG, 0);
            if (appInfo == null || (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return false;
            }
            return pm.checkPermission(PERM_BIND_EUICC, LPA_PKG) == PackageManager.PERMISSION_GRANTED
                    && pm.checkPermission(PERM_SE_PRIVILEGED, LPA_PKG) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String describeCardStatus(boolean lpaPrivMounted, TelephonyManager tm) {
        int cardType = Settings.Secure.getInt(getContentResolver(), KEY_CARD_TYPE, -1);
        int esimSwitch = Settings.Secure.getInt(getContentResolver(), KEY_ENABLE_ESIM, -1);
        int sim2State = TelephonyManager.SIM_STATE_UNKNOWN;
        if (tm != null) {
            try {
                sim2State = tm.getSimState(1);
            } catch (Throwable ignored) {
            }
        }
        if (cardType == 1) {
            if (sim2State == TelephonyManager.SIM_STATE_READY || sim2State == 10) {
                return "✅ 已识别实体 eSIM 卡（套餐已启用）";
            }
            if (esimSwitch == 0) {
                return "⚠️ 已识别实体 eSIM 卡（系统 eSIM 开关已关闭）";
            }
            return "✅ 已识别实体 eSIM 卡（空白卡/待启用，可直接写卡）";
        }
        if (cardType == 0) {
            if (sim2State == TelephonyManager.SIM_STATE_ABSENT) {
                return "❌ 卡槽 2 未插卡";
            }
            return "❌ 普通 SIM 卡或未启用适配";
        }
        if (!lpaPrivMounted) {
            return "❌ 等待 LPA 系统特权挂载";
        }
        return "⏳ 正在识别卡槽 2...";
    }

    private void refreshStatus() {
        try {
            PackageManager pm = getPackageManager();
            boolean lpaPrivMounted = isLpaPrivMounted(pm);
            boolean hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
            EuiccManager em = getSystemService(EuiccManager.class);
            boolean enabled = em != null && em.isEnabled();
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            int defaultCardId = -2;
            try {
                if (tm != null && GET_DEFAULT_CARD_ID != null) {
                    defaultCardId = (int) GET_DEFAULT_CARD_ID.invoke(tm);
                }
            } catch (Throwable ignored) {
            }
            boolean lpaSchemeOk = pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DEFAULT_MOCK_CODE)), 0) != null;
            boolean startActOk = pm.resolveActivity(new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION), 0) != null;
            String cardStatus = describeCardStatus(lpaPrivMounted, tm);

            String text = "• LPA 系统特权挂载：" + (lpaPrivMounted
                            ? "✅ 已生效"
                            : "❌ 未挂载（请检查 Hybrid Mount / meta-overlayfs）") + "\n"
                    + "• 硬件特性声明 (EUICC)：" + (hasFeature ? "✅ true" : "❌ false") + "\n"
                    + "• 服务可用 (isEnabled)：" + (enabled ? "✅ true" : "❌ false") + "\n"
                    + "• 默认 eSIM 卡槽 ID：" + (defaultCardId >= 0 ? "✅ cardId = " + defaultCardId : "❌ " + defaultCardId) + "\n"
                    + "• 卡槽 2 卡片状态：" + cardStatus + "\n"
                    + "• LPA:// 协议接管：" + (lpaSchemeOk ? "✅ 已开启" : "⚪ 已释放/关闭") + "\n"
                    + "• 标准激活跳转：" + (startActOk ? "✅ 已就绪" : "❌ 未就绪");
            statusView.setText(text);
        } catch (Throwable t) {
            statusView.setText("状态检测异常: " + t.getMessage());
        }
    }

    private void startMockDownload() {
        try {
            if (!isLpaPrivMounted(getPackageManager())) {
                appendLog("错误: LPA 系统特权挂载未生效！请检查 Hybrid Mount / meta-overlayfs 元模块并重启。");
                return;
            }
            EuiccManager em = getSystemService(EuiccManager.class);
            if (em == null || !em.isEnabled()) {
                appendLog("错误: EuiccManager 未启用，请确认模块已开启且卡槽 2 插入了实体 eSIM 卡。");
                return;
            }
            String raw = codeInput.getText() != null ? codeInput.getText().toString().trim() : "";
            if (raw.isEmpty()) {
                raw = DEFAULT_MOCK_CODE;
                codeInput.setText(raw);
            }
            appendLog("----------------------------------------");
            appendLog("发起 EuiccManager.downloadSubscription...");
            appendLog("激活码: " + raw);
            DownloadableSubscription sub = DownloadableSubscription.forActivationCode(raw);
            Intent cb = new Intent(ACTION_DOWNLOAD_CB).setPackage(getPackageName()).putExtra("phase", "initial");
            PendingIntent pi = PendingIntent.getBroadcast(
                    this,
                    ++requestSeq,
                    cb,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            em.downloadSubscription(sub, true, pi);
        } catch (Throwable t) {
            appendLog("调用异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void startLpaUriTest() {
        try {
            String raw = codeInput.getText() != null ? codeInput.getText().toString().trim() : DEFAULT_MOCK_CODE;
            if (!raw.regionMatches(true, 0, "LPA:", 0, 4)) {
                raw = "LPA:" + raw;
            }
            appendLog("----------------------------------------");
            appendLog("发起 ACTION_VIEW 跳转: " + raw);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
            startActivity(intent);
        } catch (Throwable t) {
            appendLog("LPA:// 跳转失败（请检查主界面是否关闭了 LPA:// 链接接管）: " + t.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("deprecation")
    private void startStandardActivationTest() {
        try {
            appendLog("----------------------------------------");
            appendLog("发起 ACTION_START_EUICC_ACTIVATION...");
            Intent intent = new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION);
            startActivityForResult(intent, REQ_ACTIVATE);
        } catch (Throwable t) {
            appendLog("标准激活调用失败: " + t.getClass().getSimpleName());
        }
    }

    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        logBuffer.append("[").append(ts).append("] ").append(msg).append("\n");
        if (logView != null) {
            logView.setText(logBuffer.toString());
        }
        Log.i(TAG, msg);
    }
}
