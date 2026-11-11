package io.github.local.esimh4probe;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Status dashboard and CLI probe entrypoint for the Xiaomi HyperOS 4 removable eSIM compatibility assistant.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "EsimH4Probe";
    private static final String MODULE_VERSION = "v0.5.0";
    private static final int MODULE_VERSION_CODE = 500;
    private static final String TESTER_PKG = "io.github.local.esimh4tester";
    private static final String LPA_PKG = "com.miui.euicc";
    private static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    private static final String KEY_ENABLE_ESIM = "is_enable_esim_for_user";
    private static final String PERM_BIND_EUICC = "android.permission.BIND_EUICC_SERVICE";
    private static final String PERM_SE_PRIVILEGED = "android.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION";
    private static final String PERM_MODIFY_PHONE = "android.permission.MODIFY_PHONE_STATE";

    private TextView statusText;
    private Button manageBtn;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 48, 32, 48);

        statusText = new TextView(this);
        statusText.setPadding(0, 0, 0, 32);
        statusText.setTextSize(16);
        layout.addView(statusText);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("打开系统移动网络设置");
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)));
        layout.addView(settingsBtn);

        manageBtn = new Button(this);
        manageBtn.setText("管理 eSIM");
        manageBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS));
            } catch (RuntimeException e) {
                statusText.setText("请先启用适配模块并确认 LPA 系统特权挂载已生效，重启手机后重试。");
            }
        });
        layout.addView(manageBtn);

        Button testerBtn = new Button(this);
        testerBtn.setText("打开 eSIM 模拟写卡测试");
        testerBtn.setOnClickListener(v -> {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(TESTER_PKG);
                if (launch != null) {
                    startActivity(launch);
                } else {
                    Toast.makeText(this, "请先启用模块以加载模拟写卡测试 App", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable e) {
                Toast.makeText(this, "无法启动模拟写卡测试 App", Toast.LENGTH_SHORT).show();
            }
        });
        layout.addView(testerBtn);

        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新诊断状态");
        refreshBtn.setOnClickListener(v -> refreshDashboard());
        layout.addView(refreshBtn);

        scroll.addView(layout);
        setContentView(scroll);

        refreshDashboard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
    }

    private void refreshDashboard() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                String result = inspect(MainActivity.this);
                Log.i(TAG, result);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        JSONObject report = new JSONObject(result);
                        boolean lpaPrivMounted = report.optBoolean("lpaPrivMounted", false);
                        int cardType = report.optInt("cardType", -1);
                        int esimSwitch = report.optInt("esimSwitch", -1);
                        int sim2State = report.optInt("sim2SimState", TelephonyManager.SIM_STATE_UNKNOWN);
                        Boolean hasActiveSub = report.has("sim2HasActiveSub")
                                ? report.optBoolean("sim2HasActiveSub")
                                : null;

                        manageBtn.setEnabled(lpaPrivMounted && cardType != 0);
                        String cardStatus = describeSim2CardStatus(
                                cardType, esimSwitch, sim2State, hasActiveSub, lpaPrivMounted);
                        String lpaVer = report.optString("lpaVersion", "未安装");
                        String mountLine = lpaPrivMounted
                                ? "LPA 系统特权挂载：✅ 已生效"
                                : "LPA 系统特权挂载：❌ 未挂载（请检查 KernelSU/SukiSU 元模块如 meta-overlayfs 并重启）";

                        statusText.setText("小米 eSIM 兼容助手 " + MODULE_VERSION + "\n\n"
                                + mountLine + "\n"
                                + "系统 eSIM 框架：" + (report.optBoolean("feature") ? "✅ 已加载" : "❌ 未启用") + "\n"
                                + "原生 eSIM 服务：" + (report.optBoolean("enabled") ? "✅ 可用" : "❌ 未启用") + "\n"
                                + "小米 LPA 版本：" + lpaVer + "\n"
                                + "默认 eSIM 卡槽 ID：" + report.optInt("defaultCardId", -1) + "\n"
                                + "卡2 状态：" + cardStatus + "\n\n"
                                + "系统 eSIM 开关与卡2同步启停（支持全新空白卡直接写卡），无需重启。\n"
                                + "已支持运营商 App 一键写卡授权弹窗及 LPA:// 链接跳转。\n\n"
                                + "完整功能开关：KernelSU / Magisk → 本模块 → 操作\n"
                                + "切换后请重启手机。\n\n"
                                + "适用范围：小米 HyperOS 4 (Android 15+ / SDK 35+) 双卡机型 · 卡槽 2 可插拔实体 eSIM 卡（ESTKme / 小白卡 / 9eSIM / Xesim 等兼容 SGP.22 卡片）。");
                    } catch (Exception e) {
                        statusText.setText("检查失败，请查看系统日志。");
                    }
                });
            } finally {
                refreshing.set(false);
            }
        }, "EsimH4-Inspect").start();
    }

    private static String describeSim2CardStatus(
            int cardType,
            int esimSwitch,
            int sim2State,
            Boolean hasActiveSub,
            boolean lpaPrivMounted) {
        if (cardType == 1) {
            boolean profileActive = Boolean.TRUE.equals(hasActiveSub)
                    || sim2State == TelephonyManager.SIM_STATE_READY
                    || sim2State == 10;
            if (profileActive) {
                return "已识别实体 eSIM 卡（套餐已启用就绪）";
            }
            if (esimSwitch == 0) {
                return "已识别实体 eSIM 卡（系统 eSIM 开关已关闭）";
            }
            return "已识别实体 eSIM 卡（空白卡或暂无启用套餐，可直接添加/管理 eSIM）";
        }
        if (cardType == 0) {
            if (sim2State == TelephonyManager.SIM_STATE_ABSENT) {
                return "未插卡（请将实体 eSIM 卡放入卡槽 2）";
            }
            return "普通 SIM 卡或未启用 eSIM 适配（安全保护已生效）";
        }
        if (!lpaPrivMounted) {
            return "等待 LPA 系统特权挂载生效";
        }
        if (sim2State == TelephonyManager.SIM_STATE_ABSENT) {
            return "未检测到卡槽 2 卡片";
        }
        return "正在识别卡槽 2 卡片类型...";
    }

    private static int readSecureInt(Context ctx, String key, int defaultValue) {
        if (ctx != null) {
            try {
                ContentResolver resolver = ctx.getContentResolver();
                if (resolver != null) {
                    return Settings.Secure.getInt(resolver, key);
                }
            } catch (Settings.SettingNotFoundException e) {
                return defaultValue;
            } catch (Throwable ignored) {
            }
        }
        int uid = Process.myUid();
        if (uid == 0 || uid == 2000) {
            try {
                java.lang.Process p = new ProcessBuilder(
                        "/system/bin/settings", "--user", "0", "get", "secure", key).start();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return defaultValue;
                }
                String raw = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) {
                    return defaultValue;
                }
                return Integer.parseInt(raw);
            } catch (Throwable ignored) {
            }
        }
        return defaultValue;
    }

    public static String inspect(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            out.put("version", MODULE_VERSION);
            out.put("versionCode", MODULE_VERSION_CODE);

            PackageManager pm = ctx.getPackageManager();
            out.put("feature", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC));
            EuiccManager euicc = ctx.getSystemService(EuiccManager.class);
            out.put("manager", euicc != null);
            out.put("enabled", euicc != null && euicc.isEnabled());
            out.put("manageActivity", pm.resolveActivity(
                    new Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS), 0) != null);
            out.put("lpaServices", pm.queryIntentServices(
                    new Intent("android.service.euicc.EuiccService"), 0).size());

            boolean lpaInstalled = false;
            boolean lpaSystemApp = false;
            boolean lpaUpdatedSystemApp = false;
            boolean lpaBindEuiccPerm = false;
            boolean lpaSePrivPerm = false;
            boolean lpaModifyPhonePerm = false;
            String lpaSourceDir = "";
            try {
                PackageInfo pkgInfo = pm.getPackageInfo(LPA_PKG, 0);
                lpaInstalled = true;
                out.put("lpaVersion", pkgInfo.versionName);
                out.put("lpaVersionCode", pkgInfo.getLongVersionCode());
                ApplicationInfo appInfo = pkgInfo.applicationInfo != null
                        ? pkgInfo.applicationInfo
                        : pm.getApplicationInfo(LPA_PKG, 0);
                if (appInfo != null) {
                    lpaSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    lpaUpdatedSystemApp = (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                    if (appInfo.sourceDir != null) {
                        lpaSourceDir = appInfo.sourceDir;
                    }
                }
                lpaBindEuiccPerm = pm.checkPermission(PERM_BIND_EUICC, LPA_PKG)
                        == PackageManager.PERMISSION_GRANTED;
                lpaSePrivPerm = pm.checkPermission(PERM_SE_PRIVILEGED, LPA_PKG)
                        == PackageManager.PERMISSION_GRANTED;
                lpaModifyPhonePerm = pm.checkPermission(PERM_MODIFY_PHONE, LPA_PKG)
                        == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {
            }
            boolean lpaPrivMounted = lpaInstalled && lpaSystemApp && lpaBindEuiccPerm && lpaSePrivPerm;
            out.put("lpaInstalled", lpaInstalled);
            out.put("lpaSystemApp", lpaSystemApp);
            out.put("lpaUpdatedSystemApp", lpaUpdatedSystemApp);
            out.put("lpaBindEuiccPerm", lpaBindEuiccPerm);
            out.put("lpaSePrivPerm", lpaSePrivPerm);
            out.put("lpaModifyPhonePerm", lpaModifyPhonePerm);
            out.put("lpaPrivMounted", lpaPrivMounted);
            if (!lpaSourceDir.isEmpty()) {
                out.put("lpaSourceDir", lpaSourceDir);
            }

            int cardType = readSecureInt(ctx, KEY_CARD_TYPE, -1);
            int esimSwitch = readSecureInt(ctx, KEY_ENABLE_ESIM, -1);
            out.put("cardType", cardType);
            out.put("esimSwitch", esimSwitch);

            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            int sim2State = TelephonyManager.SIM_STATE_UNKNOWN;
            if (tm != null) {
                try {
                    sim2State = tm.getSimState(1);
                } catch (Throwable ignored) {
                }
                try {
                    out.put("defaultCardId",
                            TelephonyManager.class.getMethod("getCardIdForDefaultEuicc").invoke(tm));
                } catch (Throwable e) {
                    out.put("defaultCardIdError", e.getClass().getSimpleName());
                }
            }
            out.put("sim2SimState", sim2State);

            try {
                SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
                if (sm != null) {
                    SubscriptionInfo activeSlot1 = sm.getActiveSubscriptionInfoForSimSlotIndex(1);
                    out.put("sim2HasActiveSub", activeSlot1 != null);
                }
            } catch (Throwable ignored) {
            }
            out.put("sim2EmptyOrInactiveEuicc",
                    cardType == 1 && sim2State != TelephonyManager.SIM_STATE_READY && sim2State != 10);

            try {
                String eid = euicc == null ? null : euicc.getEid();
                out.put("realEidPresent", eid != null && eid.matches("[0-9]{32}"));
                out.put("eidLength", eid == null ? 0 : eid.length());
            } catch (Throwable e) {
                out.put("eidError", e.getClass().getSimpleName());
            }
        } catch (Throwable e) {
            try {
                out.put("error", e.getClass().getName() + ": " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        Context context = EuiccCardReader.createSystemContext();
        if (args.length == 1 && "eid-hash".equals(args[0])) {
            EuiccManager em = context.getSystemService(EuiccManager.class);
            String eid = em != null ? em.getEid() : null;
            if (eid == null || !eid.matches("[0-9]{32}")) {
                throw new IllegalStateException("EID unavailable");
            }
            System.out.println("EID_SHA256=" + EuiccCardReader.hash(eid));
            System.exit(0);
            return;
        }
        System.out.println("ESIM_PROBE " + inspect(context));
        System.exit(0);
    }
}
