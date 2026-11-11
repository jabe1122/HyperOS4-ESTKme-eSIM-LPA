package io.github.local.esimh4tester;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Standalone mock carrier app to test EuiccManager one-click eSIM installation & authorization dialog. */
public final class TesterActivity extends Activity {
    private static final String TAG = "EsimH4Tester";
    private static final String MODULE_VERSION = "v0.5.0";
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
        } catch (Throwable ignored) {}
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
            Log.i(TAG, "Callback received: phase=" + phase + ", resultCode=" + resultCode + ", detailed=" + detailed + ", mock=" + mockIntercepted);

            if ("initial".equals(phase)) {
                if (resultCode == EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR) {
                    appendLog("收到系统回调: RESOLVABLE_ERROR (1)");
                    appendLog("说明: 系统已识别写卡请求，因当前 App 无运营商特权签名，要求弹出系统授权窗口。");
                    try {
                        EuiccManager em = getSystemService(EuiccManager.class);
                        Intent cb = new Intent(ACTION_DOWNLOAD_CB).setPackage(getPackageName()).putExtra("phase", "resolved");
                        PendingIntent pi = PendingIntent.getBroadcast(
                                TesterActivity.this,
                                ++requestSeq,
                                cb,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
                        );
                        appendLog("正在调用 startResolutionActivity 调起小米系统授权弹窗...");
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
                        appendLog("【安全提示】已检测到 test.esim.local 测试码，模块已在底层安全拦截，未向实体 eSIM 卡写入测试数据！");
                    } else {
                        appendLog("授权后回调: 真实激活码写卡成功 (RESULT_OK)");
                    }
                } else {
                    appendLog("授权后回调结束: resultCode=" + resultCode + " (用户取消或写卡中止，未修改卡内数据)");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerReceiver(downloadReceiver, new IntentFilter(ACTION_DOWNLOAD_CB), Context.RECEIVER_EXPORTED);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#F7F8FA"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 56, 40, 48);

        TextView title = new TextView(this);
        title.setText("eSIM 模拟运营商写卡测试 " + MODULE_VERSION);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#111111"));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("面向小米 HyperOS 4 (Android 15+ / SDK 35+) 双卡机型与卡槽 2 可插拔实体 eSIM 卡（ESTKme / 小白卡 / 9eSIM / Xesim 等）。模拟第三方运营商 App 检测 eSIM 支持并调起小米系统原生写卡授权窗口。模块关闭或卸载时本应用会自动清理。");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.parseColor("#555555"));
        subtitle.setPadding(0, 12, 0, 24);
        root.addView(subtitle);

        statusView = new TextView(this);
        statusView.setBackgroundColor(Color.WHITE);
        statusView.setPadding(32, 28, 32, 28);
        statusView.setTextSize(14);
        statusView.setTextColor(Color.parseColor("#222222"));
        root.addView(statusView);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新 eSIM 环境状态");
        refreshBtn.setOnClickListener(v -> refreshStatus());
        root.addView(refreshBtn);

        TextView codeLabel = new TextView(this);
        codeLabel.setText("\n测试激活码（默认使用安全模拟码 test.esim.local，即使在弹窗点确定也不会写入实体卡）：");
        codeLabel.setTextSize(13);
        codeLabel.setTextColor(Color.parseColor("#333333"));
        root.addView(codeLabel);

        codeInput = new EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setText(DEFAULT_MOCK_CODE);
        codeInput.setTextSize(14);
        codeInput.setBackgroundColor(Color.WHITE);
        codeInput.setPadding(24, 20, 24, 20);
        root.addView(codeInput);

        Button resetCodeBtn = new Button(this);
        resetCodeBtn.setText("恢复默认安全测试码");
        resetCodeBtn.setOnClickListener(v -> {
            codeInput.setText(DEFAULT_MOCK_CODE);
            appendLog("已恢复默认安全测试激活码: " + DEFAULT_MOCK_CODE);
        });
        root.addView(resetCodeBtn);

        Button testDownloadBtn = new Button(this);
        testDownloadBtn.setText("测试 1：模拟运营商一键写卡（调起系统授权弹窗）");
        testDownloadBtn.setOnClickListener(v -> startMockDownload());
        root.addView(testDownloadBtn);

        Button testUriBtn = new Button(this);
        testUriBtn.setText("测试 2：模拟点击 LPA:// 链接（跳转小米写卡页）");
        testUriBtn.setOnClickListener(v -> startLpaUriTest());
        root.addView(testUriBtn);

        Button testActionBtn = new Button(this);
        testActionBtn.setText("测试 3：调起系统标准扫码写卡 (START_EUICC_ACTIVATION)");
        testActionBtn.setOnClickListener(v -> startStandardActivationTest());
        root.addView(testActionBtn);

        TextView logHeader = new TextView(this);
        logHeader.setText("\n实时测试日志：");
        logHeader.setTextSize(14);
        logHeader.setTypeface(Typeface.DEFAULT_BOLD);
        logHeader.setTextColor(Color.parseColor("#222222"));
        root.addView(logHeader);

        logView = new TextView(this);
        logView.setBackgroundColor(Color.parseColor("#1E1E1E"));
        logView.setTextColor(Color.parseColor("#00E676"));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(12);
        logView.setPadding(28, 24, 28, 24);
        logView.setMinLines(8);
        logView.setGravity(Gravity.TOP | Gravity.START);
        root.addView(logView);

        scroll.addView(root);
        setContentView(scroll);

        refreshStatus();
        appendLog("就绪。点击上方「测试 1」即可模拟运营商 App 调起小米系统 eSIM 授权弹窗。");

        if (getIntent() != null && getIntent().getBooleanExtra("auto_test", false)) {
            root.postDelayed(this::startMockDownload, 400);
        }
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
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESOLVE) {
            appendLog("系统授权窗口返回: resultCode=" + (resultCode == Activity.RESULT_OK ? "RESULT_OK (-1，用户点击了确定/开启)" : "RESULT_CANCELED (0，用户点击了取消)"));
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
            } catch (Throwable ignored) {}
        }
        if (cardType == 1) {
            if (sim2State == TelephonyManager.SIM_STATE_READY || sim2State == 10) {
                return "✅ 已识别实体 eSIM 卡（套餐已启用）";
            }
            if (esimSwitch == 0) {
                return "⚠️ 已识别实体 eSIM 卡（系统 eSIM 开关已关闭）";
            }
            return "✅ 已识别实体 eSIM 卡（空白卡/暂无启用套餐，可写卡）";
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
            } catch (Throwable ignored) {}
            boolean lpaSchemeOk = pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DEFAULT_MOCK_CODE)), 0) != null;
            boolean startActOk = pm.resolveActivity(new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION), 0) != null;
            String cardStatus = describeCardStatus(lpaPrivMounted, tm);

            String text = "【当前运营商 App 视角检测结果 · " + MODULE_VERSION + "】\n"
                    + "• LPA 系统特权挂载：" + (lpaPrivMounted
                            ? "✅ 已生效"
                            : "❌ 未挂载（请检查 KernelSU/SukiSU 元模块如 meta-overlayfs 并重启）") + "\n"
                    + "• 硬件特性 (FEATURE_TELEPHONY_EUICC): " + (hasFeature ? "✅ 已声明 (true)" : "❌ 未声明 (false)") + "\n"
                    + "• 服务可用 (EuiccManager.isEnabled): " + (enabled ? "✅ 已启用 (true)" : "❌ 未启用 (false)") + "\n"
                    + "• 默认 eSIM 卡槽 (defaultCardId): " + (defaultCardId >= 0 ? "✅ cardId = " + defaultCardId : "❌ " + defaultCardId) + "\n"
                    + "• 卡槽 2 实体卡状态: " + cardStatus + "\n"
                    + "• LPA:// 协议跳转支持: " + (lpaSchemeOk ? "✅ 已就绪" : "❌ 未就绪") + "\n"
                    + "• 标准激活 Action 支持: " + (startActOk ? "✅ 已就绪" : "❌ 未就绪");
            statusView.setText(text);
        } catch (Throwable t) {
            statusView.setText("状态检测异常: " + t.getMessage());
        }
    }

    private void startMockDownload() {
        try {
            if (!isLpaPrivMounted(getPackageManager())) {
                appendLog("错误: LPA 系统特权挂载未生效！请检查 KernelSU/SukiSU 是否已安装并启用 meta-overlayfs / magic_mount 等元模块，重启后再试。");
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
            appendLog("LPA:// 跳转失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void startStandardActivationTest() {
        try {
            appendLog("----------------------------------------");
            appendLog("发起 ACTION_START_EUICC_ACTIVATION...");
            Intent intent = new Intent(EuiccManager.ACTION_START_EUICC_ACTIVATION);
            startActivityForResult(intent, REQ_ACTIVATE);
        } catch (Throwable t) {
            appendLog("标准激活调用失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
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
