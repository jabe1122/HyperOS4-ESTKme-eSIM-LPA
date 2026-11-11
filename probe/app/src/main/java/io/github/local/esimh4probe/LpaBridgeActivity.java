package io.github.local.esimh4probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.euicc.DownloadableSubscription;
import android.widget.Toast;

/**
 * Translucent dispatcher activity that bridges standard LPA: activation URIs
 * and START_EUICC_ACTIVATION intents to Xiaomi's native MIUIEsimLPA UI.
 */
public final class LpaBridgeActivity extends Activity {
    private static final String LPA_PACKAGE = "com.miui.euicc";
    private static final String ACTIVE_STATUS_ACTIVITY = "com.miui.euicc.ui.active.ActiveStatusActivity";
    private static final String CAPTURE_ACTIVITY = "com.miui.euicc.ui.active.CaptureActivity";
    private static final String EXTRA_DOWNLOADABLE_SUB =
            "android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            int cardType = Settings.Secure.getInt(getContentResolver(), CardTypeGuard.KEY_CARD_TYPE, -2);
            if (cardType == -2) {
                Bundle status = ProbeInspector.callPhoneCtrl(this, "status", null);
                if (status != null && status.getBoolean("ok", false)) {
                    cardType = status.getInt("card_type", 0);
                } else {
                    cardType = 0;
                }
            }
            if (!EuiccCardReader.isModuleActive() || cardType != 1) {
                String msg = (EuiccCardReader.isModuleActive() && cardType == -1)
                        ? "正在识别卡槽2中的SIM卡类型，请稍候重试"
                        : "卡槽2未检测到可用eSIM卡或模块未启用";
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                setResult(Activity.RESULT_CANCELED);
                return;
            }

            String code = extractActivationCode(getIntent());
            Intent target = new Intent();
            if (code != null && !code.isEmpty()) {
                target.setComponent(new ComponentName(LPA_PACKAGE, ACTIVE_STATUS_ACTIVITY));
                target.putExtra("com.miui.euicc.extra.ACTIVATE_TYPE", "activate_manual");
                target.putExtra("com.miui.euicc.extra.ACTIVATE_CODE", code);
                target.putExtra("com.miui.euicc.extra.ACTIVATE_CHANNEL", "lpa");
                target.putExtra("com.miui.euicc.extra.OPERATE_TYPE", "manual");
            } else {
                target.setComponent(new ComponentName(LPA_PACKAGE, CAPTURE_ACTIVITY));
            }
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(target);
            setResult(Activity.RESULT_OK);
        } catch (Throwable t) {
            Toast.makeText(this, "无法调起小米 eSIM 写卡页面: " + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
            setResult(Activity.RESULT_CANCELED);
        } finally {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    private static String extractActivationCode(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data != null) {
            String raw = data.toString();
            if (isActivationCode(raw)) {
                return raw.trim();
            }
        }
        String extra = intent.getStringExtra("com.miui.euicc.extra.ACTIVATE_CODE");
        if (extra != null && !extra.trim().isEmpty()) {
            return extra.trim();
        }
        extra = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (isActivationCode(extra)) {
            return extra.trim();
        }
        try {
            DownloadableSubscription sub = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(EXTRA_DOWNLOADABLE_SUB, DownloadableSubscription.class)
                    : (DownloadableSubscription) intent.getParcelableExtra(EXTRA_DOWNLOADABLE_SUB);
            if (sub != null) {
                String encoded = sub.getEncodedActivationCode();
                if (encoded != null && !encoded.trim().isEmpty()) {
                    return encoded.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isActivationCode(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return trimmed.regionMatches(true, 0, "LPA:", 0, 4) || trimmed.startsWith("1$");
    }
}
