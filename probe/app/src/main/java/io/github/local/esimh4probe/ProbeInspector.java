package io.github.local.esimh4probe;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.euicc.EuiccManager;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Pure Android SDK inspector and CLI probe runner (zero AndroidX/UI imports so
 * app_process CLI invocations remain lightweight and dependency-free).
 */
public final class ProbeInspector {
    static final String MODULE_VERSION = "v0.6.0";
    static final int MODULE_VERSION_CODE = 600;
    static final String TESTER_PKG = "io.github.local.esimh4tester";
    static final String LPA_PKG = "com.miui.euicc";
    static final Uri PHONE_CTRL_URI = Uri.parse("content://service-state");
    static final Uri LPA_PROVIDER_URI = Uri.parse("content://com.miui.euicc.provider");

    private static final String PERM_BIND_EUICC = "android.permission.BIND_EUICC_SERVICE";
    private static final String PERM_SE_PRIVILEGED = "android.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION";
    private static final String PERM_MODIFY_PHONE = "android.permission.MODIFY_PHONE_STATE";
    private static final Method SYS_PROP_GET;

    static {
        Method m = null;
        try {
            m = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
        } catch (Throwable ignored) {
        }
        SYS_PROP_GET = m;
    }

    private ProbeInspector() {}

    static String getProp(String key, String def) {
        if (SYS_PROP_GET == null) return def;
        try {
            String val = (String) SYS_PROP_GET.invoke(null, key, def);
            return val != null ? val : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    static Bundle callPhoneCtrl(Context ctx, String subcmd, Bundle extras) {
        if (ctx != null) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                if (cr != null) {
                    Bundle res = cr.call(PHONE_CTRL_URI, CompatModule.CTRL_METHOD, subcmd, extras);
                    if (res != null) return res;
                }
            } catch (Throwable ignored) {
            }
        }
        int uid = Process.myUid();
        if ((uid == 0 || uid == 2000) && ("status".equals(subcmd) || subcmd == null) && extras == null) {
            String raw = runContentCall("content://service-state", CompatModule.CTRL_METHOD, "status");
            if (raw.contains("phone_hook=true")) {
                Bundle b = new Bundle();
                b.putBoolean("ok", true);
                b.putBoolean("phone_hook", true);
                b.putBoolean("carrier_bridge", !raw.contains("carrier_bridge=false"));
                b.putBoolean("rewrite_text", !raw.contains("rewrite_text=false"));
                return b;
            }
        }
        return null;
    }

    private static boolean pingLpaHook(Context ctx) {
        if (ctx != null) {
            try {
                ContentResolver cr = ctx.getContentResolver();
                if (cr != null) {
                    Bundle b = cr.call(LPA_PROVIDER_URI, EuiccCardReader.PROBE_METHOD, "ping", null);
                    if (b != null && b.getBoolean("lpa_hook", false)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        int uid = Process.myUid();
        if (uid == 0 || uid == 2000) {
            String raw = runContentCall("content://com.miui.euicc.provider", EuiccCardReader.PROBE_METHOD, "ping");
            return raw.contains("lpa_hook=true");
        }
        return false;
    }

    private static String runContentCall(String uri, String method, String arg) {
        try {
            java.lang.Process p = new ProcessBuilder(
                    "/system/bin/content", "call", "--uri", uri, "--method", method, "--arg", arg).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "";
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static int readSecureInt(Context ctx, String key, int defaultValue) {
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
            out.put("device", Build.DEVICE);
            out.put("incremental", Build.VERSION.INCREMENTAL);
            out.put("sdk", Build.VERSION.SDK_INT);

            boolean propActive = "1".equals(getProp("sys.xiaomi_esim_h4.active", "0"));
            boolean propMountOk = "1".equals(getProp("sys.xiaomi_esim_h4.mount_ok", "0"));
            out.put("moduleActive", propActive);
            out.put("propMountOk", propMountOk);

            PackageManager pm = ctx.getPackageManager();
            boolean hasFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC);
            out.put("feature", hasFeature);
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

            boolean testerInstalled = false;
            try {
                pm.getPackageInfo(TESTER_PKG, 0);
                testerInstalled = true;
            } catch (Throwable ignored) {
            }
            out.put("testerInstalled", testerInstalled);

            boolean lpaBridgeCompEnabled = true;
            try {
                ComponentName bridgeComp = new ComponentName(
                        "io.github.jabe1122.esimh4probe",
                        "io.github.local.esimh4probe.LpaBridgeActivity");
                int compState = pm.getComponentEnabledSetting(bridgeComp);
                lpaBridgeCompEnabled = compState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        || compState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            } catch (Throwable ignored) {
            }
            out.put("lpaUriBridgeEnabled", lpaBridgeCompEnabled);

            int cardType = readSecureInt(ctx, CardTypeGuard.KEY_CARD_TYPE, -1);
            int esimSwitch = readSecureInt(ctx, CardTypeGuard.KEY_ENABLE_ESIM, -1);
            boolean carrierBridge = readSecureInt(ctx, CardTypeGuard.KEY_CARRIER_BRIDGE, 1) != 0;
            boolean rewriteText = readSecureInt(ctx, CardTypeGuard.KEY_REWRITE_TEXT, 1) != 0;

            boolean phoneHookActive = false;
            boolean phoneHasActiveSub = false;
            String phoneCarrierName = "";
            Bundle phoneStatus = callPhoneCtrl(ctx, "status", null);
            if (phoneStatus != null && phoneStatus.getBoolean("ok", false)) {
                phoneHookActive = phoneStatus.getBoolean("phone_hook", false);
                cardType = phoneStatus.getInt("card_type", cardType);
                esimSwitch = phoneStatus.getInt("esim_switch", esimSwitch);
                carrierBridge = phoneStatus.getBoolean("carrier_bridge", carrierBridge);
                rewriteText = phoneStatus.getBoolean("rewrite_text", rewriteText);
                phoneHasActiveSub = phoneStatus.getBoolean("sim2_has_active_sub", false);
                phoneCarrierName = phoneStatus.getString("sim2_carrier_name", "");
            }
            out.put("phoneHookActive", phoneHookActive);
            out.put("lpaHookActive", lpaPrivMounted && pingLpaHook(ctx));
            out.put("cardType", cardType);
            out.put("esimSwitch", esimSwitch);
            out.put("carrierBridgeEnabled", carrierBridge);
            out.put("rewriteTextEnabled", rewriteText);

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

            boolean hasActiveSub = phoneHasActiveSub;
            String carrierName = phoneCarrierName != null ? phoneCarrierName : "";
            try {
                SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
                if (sm != null) {
                    SubscriptionInfo activeSlot1 = sm.getActiveSubscriptionInfoForSimSlotIndex(1);
                    hasActiveSub = activeSlot1 != null;
                    if (activeSlot1 != null && activeSlot1.getDisplayName() != null) {
                        String carrier = activeSlot1.getDisplayName().toString().trim();
                        if (!carrier.isEmpty()) {
                            carrierName = carrier;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            out.put("sim2HasActiveSub", hasActiveSub);
            if (!carrierName.isEmpty()) {
                out.put("sim2CarrierName", carrierName);
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
