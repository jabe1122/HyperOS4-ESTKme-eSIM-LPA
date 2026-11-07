package io.github.local.esimh4probe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/**
 * Status dashboard and CLI probe entrypoint for the MIX Flip 2 HyperOS 4 eSIM compatibility assistant.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "EsimH4Probe";
    private static final String TESTER_PKG = "io.github.local.esimh4tester";
    private static final String LPA_PKG = "com.miui.euicc";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView text = new TextView(this);
        text.setPadding(32, 48, 32, 32);
        text.setTextSize(17);
        layout.addView(text);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("打开系统移动网络设置");
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)));
        layout.addView(settingsBtn);

        Button manageBtn = new Button(this);
        manageBtn.setText("管理 eSIM");
        manageBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS));
            } catch (RuntimeException e) {
                text.setText("请先启用 KernelSU 适配模块并重启手机。");
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

        setContentView(layout);

        new Thread(() -> {
            String result = inspect(this);
            Log.i(TAG, result);
            runOnUiThread(() -> {
                try {
                    JSONObject report = new JSONObject(result);
                    int cardType = Settings.Secure.getInt(getContentResolver(), CardTypeGuard.KEY_CARD_TYPE, -1);
                    manageBtn.setEnabled(cardType != 0);
                    String cardStatus = cardType == 1 ? "已识别eSIM卡" : (cardType == 0 ? "未检测到可用eSIM卡" : "正在识别");
                    String lpaVer = report.optString("lpaVersion", "未知");
                    text.setText("小米 eSIM 兼容助手\n\n"
                            + "系统 eSIM 框架：" + (report.optBoolean("feature") ? "已加载" : "未启用") + "\n"
                            + "原生 eSIM 服务：" + (report.optBoolean("enabled") ? "可用" : "未启用") + "\n"
                            + "小米 LPA 版本：" + lpaVer + "\n"
                            + "默认 eSIM 卡槽 ID：" + report.optInt("defaultCardId", -1) + "\n"
                            + "卡2：" + cardStatus + "\n\n"
                            + "系统eSIM开关与卡2同步启停，无需重启。\n"
                            + "已支持运营商 App 一键写卡授权弹窗及 LPA:// 链接跳转。\n\n"
                            + "完整功能开关：KernelSU → 本模块 → 操作\n"
                            + "切换后请重启手机。\n\n"
                            + "仅适配 MIX Flip 2 / OS4.0.0.11 / ESTKme 卡2。");
                } catch (Exception e) {
                    text.setText("检查失败，请查看系统日志。");
                }
            });
        }, "EsimH4-Inspect").start();
    }

    public static String inspect(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            PackageManager pm = ctx.getPackageManager();
            out.put("feature", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC));
            EuiccManager euicc = ctx.getSystemService(EuiccManager.class);
            out.put("manager", euicc != null);
            out.put("enabled", euicc != null && euicc.isEnabled());
            out.put("manageActivity", pm.resolveActivity(new Intent(EuiccManager.ACTION_MANAGE_EMBEDDED_SUBSCRIPTIONS), 0) != null);
            out.put("lpaServices", pm.queryIntentServices(new Intent("android.service.euicc.EuiccService"), 0).size());

            try {
                PackageInfo pkgInfo = pm.getPackageInfo(LPA_PKG, 0);
                out.put("lpaVersion", pkgInfo.versionName);
                out.put("lpaVersionCode", pkgInfo.getLongVersionCode());
            } catch (Throwable ignored) {
            }

            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            try {
                out.put("defaultCardId", TelephonyManager.class.getMethod("getCardIdForDefaultEuicc").invoke(tm));
            } catch (Throwable e) {
                out.put("defaultCardIdError", e.getClass().getSimpleName());
            }

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
