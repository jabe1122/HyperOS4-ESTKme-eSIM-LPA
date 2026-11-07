package io.github.local.esimh4probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Monitors physical Slot 2 (SIM2) card state and verifies via ISD-R EID probe whether
 * the inserted card is a genuine removable eUICC. Fails closed on card removal or replacement
 * so ordinary SIM cards are never affected by eSIM operations.
 */
final class CardTypeGuard {
    private static final String TAG = "EsimH4Compat";
    static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    static final String KEY_VERIFIED_ICCID = "xiaomi_esim_h4_verified_iccid";
    static final String KEY_VERIFIED_EID = "xiaomi_esim_h4_verified_eid";
    static final String KEY_ENABLE_ESIM = "is_enable_esim_for_user";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EsimH4-CardGuard");
        t.setDaemon(true);
        return t;
    });

    private static String cachedKey = "";
    private static String inFlightKey = "";
    private static boolean cachedEuicc = false;
    private static long checkedAt = 0L;
    private static boolean observing = false;

    private CardTypeGuard() {}

    static synchronized void start(Context ctx) {
        if (observing || ctx == null) return;
        observing = true;
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;

        IntentFilter filter = new IntentFilter("android.intent.action.SIM_STATE_CHANGED");
        filter.addAction("android.telephony.action.SIM_CARD_STATE_CHANGED");
        filter.addAction("android.telephony.action.SIM_APPLICATION_STATE_CHANGED");

        appCtx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!EuiccCardReader.isModuleActive()) return;
                int slot = intent.getIntExtra("android.telephony.extra.SLOT_INDEX",
                        intent.getIntExtra("phone", -1));
                if (slot >= 0 && slot != 1) return;

                WORKER.execute(() -> {
                    if (!EuiccCardReader.isModuleActive()) return;
                    String current = slot1Key(appCtx);
                    boolean sameVerified;
                    synchronized (CardTypeGuard.class) {
                        sameVerified = !current.isEmpty() && current.equals(cachedKey) && cachedEuicc;
                        if (!sameVerified) {
                            checkedAt = 0L;
                        }
                    }
                    if (!sameVerified) {
                        Settings.Secure.putInt(appCtx.getContentResolver(), KEY_CARD_TYPE, current.isEmpty() ? 0 : -1);
                    }
                    if (isEuicc(appCtx)) {
                        Settings.Secure.putInt(appCtx.getContentResolver(), KEY_CARD_TYPE, 1);
                    }
                });
            }
        }, filter, Context.RECEIVER_EXPORTED);

        if (EuiccCardReader.isModuleActive()) {
            WORKER.execute(() -> isEuicc(appCtx));
        }
    }

    private static String slot1Key(Context ctx) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm == null || tm.getSimState(1) == TelephonyManager.SIM_STATE_ABSENT) {
                return "";
            }
            String physIccid = Sim2Subscription.physicalKey(ctx);
            if (!physIccid.isEmpty()) {
                return physIccid;
            }
            List<UiccCardInfo> cards = tm.getUiccCardsInfo();
            if (cards != null) {
                for (UiccCardInfo card : cards) {
                    if (card == null || card.getPhysicalSlotIndex() != 1) continue;
                    if (tm.getSimState(1) != TelephonyManager.SIM_STATE_UNKNOWN && card.getPorts() != null && !card.getPorts().isEmpty()) {
                        return "SLOT1_PRESENT_NO_ICCID_" + card.getCardId();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean matchesVerifiedIccid(Context ctx, String key) {
        if (key.isEmpty() || key.startsWith("SLOT1_")) return false;
        try {
            if (Settings.Secure.getInt(ctx.getContentResolver(), KEY_CARD_TYPE, 0) != 1) {
                return false;
            }
            String verified = Settings.Secure.getString(ctx.getContentResolver(), KEY_VERIFIED_ICCID);
            return verified != null && !verified.isEmpty() && verified.equals(EuiccCardReader.hash(key));
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isEuicc(Context ctx) {
        if (ctx == null || !EuiccCardReader.isModuleActive()) return false;
        String key = slot1Key(ctx);
        if (key.isEmpty()) {
            synchronized (CardTypeGuard.class) {
                cachedKey = "";
                cachedEuicc = false;
            }
            Settings.Secure.putInt(ctx.getContentResolver(), KEY_CARD_TYPE, 0);
            return false;
        }
        synchronized (CardTypeGuard.class) {
            if (key.equals(cachedKey) && (cachedEuicc || SystemClock.elapsedRealtime() - checkedAt < 5000L)) {
                return cachedEuicc;
            }
            if (!cachedEuicc && checkedAt == 0L && matchesVerifiedIccid(ctx, key)) {
                cachedKey = key;
                cachedEuicc = true;
            }
            if (!key.equals(inFlightKey)) {
                inFlightKey = key;
                Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                WORKER.execute(() -> verifySlot1Card(appCtx, key));
            }
            return key.equals(cachedKey) && cachedEuicc;
        }
    }

    static boolean isEuiccForSwitch(Context ctx) {
        if (isEuicc(ctx)) return true;
        String key = slot1Key(ctx);
        if (key.isEmpty()) return false;
        return verifySlot1Card(ctx, key);
    }

    private static boolean verifySlot1Card(Context ctx, String expectedKey) {
        boolean isEuiccResult;
        boolean confirmedNormal = false;
        long token = Binder.clearCallingIdentity();
        try {
            String eidHash = EuiccCardReader.identity(ctx);
            String physKey = Sim2Subscription.physicalKey(ctx);
            Settings.Secure.putString(ctx.getContentResolver(), KEY_VERIFIED_ICCID,
                    physKey.isEmpty() ? "" : EuiccCardReader.hash(physKey));
            Settings.Secure.putString(ctx.getContentResolver(), KEY_VERIFIED_EID, eidHash);
            isEuiccResult = true;
        } catch (Throwable e) {
            Log.w(TAG, "eUICC check unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            confirmedNormal = "eUICC probe is normal".equals(e.getMessage());
            synchronized (CardTypeGuard.class) {
                isEuiccResult = !confirmedNormal && expectedKey.equals(cachedKey) && cachedEuicc;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (!expectedKey.equals(slot1Key(ctx))) {
            synchronized (CardTypeGuard.class) {
                if (expectedKey.equals(inFlightKey)) {
                    inFlightKey = "";
                }
            }
            return false;
        }

        synchronized (CardTypeGuard.class) {
            cachedKey = expectedKey;
            cachedEuicc = isEuiccResult;
            checkedAt = SystemClock.elapsedRealtime();
            inFlightKey = "";
        }

        if (confirmedNormal) {
            Settings.Secure.putInt(ctx.getContentResolver(), KEY_ENABLE_ESIM, 0);
        }
        Settings.Secure.putInt(ctx.getContentResolver(), KEY_CARD_TYPE, isEuiccResult ? 1 : 0);
        ctx.getContentResolver().notifyChange(Settings.Secure.getUriFor(KEY_ENABLE_ESIM), null);
        Log.i(TAG, "SIM2 card type checked: eUICC=" + isEuiccResult + " (card identifiers omitted)");
        return isEuiccResult;
    }
}
