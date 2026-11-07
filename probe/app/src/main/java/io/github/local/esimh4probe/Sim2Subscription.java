package io.github.local.esimh4probe;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Process;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.UiccPortInfo;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Resolves and controls the physical Slot 2 (SIM2 / index 1) UICC subscription,
 * preserving its subscription ID across UICC application disable/enable cycles.
 */
final class Sim2Subscription {
    static final String KEY_PINNED_SUB_ID = "xiaomi_esim_h4_sim2_sub_id";
    private static final Method ARE_APPS_ENABLED;
    private static final Method SET_APPS_ENABLED;

    static {
        Method areEnabled = null;
        Method setEnabled = null;
        try {
            areEnabled = SubscriptionInfo.class.getMethod("areUiccApplicationsEnabled");
            setEnabled = SubscriptionManager.class.getMethod("setUiccApplicationsEnabled", int.class, boolean.class);
        } catch (Throwable ignored) {
        }
        ARE_APPS_ENABLED = areEnabled;
        SET_APPS_ENABLED = setEnabled;
    }

    private Sim2Subscription() {}

    private static String normalizeIccid(String value) {
        if (value == null || value.isEmpty()) return "";
        String upper = value.trim().toUpperCase(Locale.ROOT);
        int end = upper.length();
        while (end > 0 && upper.charAt(end - 1) == 'F') {
            end--;
        }
        return upper.substring(0, end);
    }

    static String physicalKey(Context ctx) {
        if (ctx == null) return "";
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm == null) return "";
            List<UiccCardInfo> cards = tm.getUiccCardsInfo();
            if (cards == null) return "";
            for (UiccCardInfo card : cards) {
                if (card == null || card.getPhysicalSlotIndex() != 1) continue;
                Collection<UiccPortInfo> ports = card.getPorts();
                if (ports == null) continue;
                for (UiccPortInfo port : ports) {
                    if (port == null) continue;
                    String iccid = normalizeIccid(port.getIccId());
                    if (!iccid.isEmpty()) return iccid;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    static SubscriptionInfo resolve(Context ctx) throws Exception {
        SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
        if (sm == null) throw new IllegalStateException("SubscriptionManager unavailable");
        List<SubscriptionInfo> all = sm.getAllSubscriptionInfoList();
        if (all == null) throw new IllegalStateException("Subscription list unavailable");

        String physIccid = physicalKey(ctx);
        if (!physIccid.isEmpty()) {
            for (SubscriptionInfo info : all) {
                if (physIccid.equals(normalizeIccid(info.getIccId()))) {
                    return pinSubId(ctx, info);
                }
            }
            throw new IllegalStateException("Physical SIM2 subscription not registered yet");
        }

        SubscriptionInfo activeSlot1 = sm.getActiveSubscriptionInfoForSimSlotIndex(1);
        if (activeSlot1 != null) {
            return pinSubId(ctx, activeSlot1);
        }

        int pinnedId = readPinnedSubId(ctx);
        if (pinnedId >= 0) {
            for (SubscriptionInfo info : all) {
                if (info.getSubscriptionId() == pinnedId) {
                    return info;
                }
            }
        }
        throw new IllegalStateException("Physical SIM2 subscription unavailable");
    }

    private static SubscriptionInfo pinSubId(Context ctx, SubscriptionInfo info) {
        if (Process.myUid() != 0 && ctx != null) {
            try {
                Settings.Secure.putInt(ctx.getContentResolver(), KEY_PINNED_SUB_ID, info.getSubscriptionId());
            } catch (Throwable ignored) {
            }
        }
        return info;
    }

    private static int readPinnedSubId(Context ctx) {
        String raw = readSecureSetting(ctx, KEY_PINNED_SUB_ID);
        if (raw == null || raw.isEmpty() || "null".equalsIgnoreCase(raw)) return -1;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static String readSecureSetting(Context ctx, String key) {
        if (ctx != null) {
            try {
                ContentResolver resolver = ctx.getContentResolver();
                if (resolver != null) {
                    String val = Settings.Secure.getString(resolver, key);
                    if (val != null) return val.trim();
                }
            } catch (Throwable ignored) {
            }
        }
        if (Process.myUid() == 0) {
            try {
                java.lang.Process p = new ProcessBuilder("/system/bin/settings", "get", "secure", key).start();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return "";
                }
                String val = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                return val;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    static boolean enabled(SubscriptionInfo info) throws Exception {
        if (ARE_APPS_ENABLED != null) {
            return (boolean) ARE_APPS_ENABLED.invoke(info);
        }
        return (boolean) SubscriptionInfo.class.getMethod("areUiccApplicationsEnabled").invoke(info);
    }

    static void set(Context ctx, boolean enabled) throws Exception {
        SubscriptionInfo info = resolve(ctx);
        SubscriptionManager sm = ctx.getSystemService(SubscriptionManager.class);
        if (SET_APPS_ENABLED != null) {
            SET_APPS_ENABLED.invoke(sm, info.getSubscriptionId(), enabled);
        } else {
            sm.getClass()
                    .getMethod("setUiccApplicationsEnabled", int.class, boolean.class)
                    .invoke(sm, info.getSubscriptionId(), enabled);
        }
    }
}
