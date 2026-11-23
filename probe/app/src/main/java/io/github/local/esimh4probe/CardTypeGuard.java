package io.github.local.esimh4probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * Monitors physical Slot 2 (SIM2) card state and verifies via ISD-R EID probe whether
 * the inserted card is a genuine removable eUICC (including brand-new empty eUICC cards
 * with no enabled profiles yet). Uses bounded backoff retries across cold-boot/reset
 * transient "unknown" states and fails closed immediately on ordinary SIM cards or card removal.
 */
final class CardTypeGuard {
    private static final String TAG = "EsimH4Compat";
    static final String KEY_CARD_TYPE = "xiaomi_esim_h4_card_type";
    static final String KEY_VERIFIED_ICCID = "xiaomi_esim_h4_verified_iccid";
    static final String KEY_VERIFIED_EID = "xiaomi_esim_h4_verified_eid";
    static final String KEY_ENABLE_ESIM = "is_enable_esim_for_user";
    static final String KEY_CARRIER_BRIDGE = "xiaomi_esim_h4_carrier_bridge";
    static final String KEY_LPA_URI_BRIDGE = "xiaomi_esim_h4_lpa_uri_bridge";
    static final String KEY_REWRITE_TEXT = "xiaomi_esim_h4_rewrite_text";
    /** 1 when the eSIM switch was turned off automatically because SIM2 was removed while bridged. */
    static final String KEY_ESIM_SUSPENDED = "xiaomi_esim_h4_esim_suspended";
    private static final String SLOT1_EMPTY_KEY = "SLOT1_PRESENT_EMPTY";
    private static final long[] RETRY_DELAYS_MS = {1500L, 3000L, 5000L, 8000L};
    private static final int MAX_UNKNOWN_STATE_RETRIES = 4;
    private static final long[] PHYSICAL_RESTORE_DELAYS_MS = {2000L, 8000L, 30000L};

    private static volatile ToIntFunction<String> physicalRestorer;
    // One restore sequence per removal episode; reset when a card is seen in SIM2 again.
    private static boolean physicalRestoreDone = false;
    private static boolean physicalRestoreScheduled = false;
    private static int physicalRestoreAttempt = 0;

    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "EsimH4-CardGuard");
        t.setDaemon(true);
        return t;
    });

    private static String cachedKey = "";
    private static String inFlightKey = "";
    private static boolean cachedEuicc = false;
    private static long checkedAt = 0L;
    private static String retryKey = "";
    private static int retryAttempt = 0;
    private static boolean retryScheduled = false;
    private static int unknownStateRetries = 0;
    private static boolean unknownStateScheduled = false;
    private static boolean observing = false;
    private static volatile boolean carrierBridgeEnabled = true;
    private static volatile boolean rewriteTextEnabled = true;

    private CardTypeGuard() {}

    static void setPhysicalRestorer(ToIntFunction<String> restorer) {
        physicalRestorer = restorer;
    }

    /**
     * Fail-safe for SIM2 removed while the bridge is active: turn the eSIM switch off (remembering
     * that it was on) so Xiaomi's native logic does not replace SIM2 with the soldered eSIM, then
     * ask the native manager once to select the physical card on both slots.
     */
    private static void onSim2Removed(Context ctx) {
        int enabled;
        int suspended;
        try {
            enabled = Settings.Secure.getInt(ctx.getContentResolver(), KEY_ENABLE_ESIM, 0);
            suspended = Settings.Secure.getInt(ctx.getContentResolver(), KEY_ESIM_SUSPENDED, 0);
        } catch (Throwable ignored) {
            return;
        }
        if (enabled == 1) {
            Settings.Secure.putInt(ctx.getContentResolver(), KEY_ESIM_SUSPENDED, 1);
            Settings.Secure.putInt(ctx.getContentResolver(), KEY_ENABLE_ESIM, 0);
            ctx.getContentResolver().notifyChange(Settings.Secure.getUriFor(KEY_ENABLE_ESIM), null);
            Log.i(TAG, "SIM2 removed while bridged; eSIM switch suspended until a verified eUICC returns");
        } else if (suspended != 1) {
            return;
        }
        schedulePhysicalRestore(ctx);
    }

    private static void schedulePhysicalRestore(Context ctx) {
        long delay;
        synchronized (CardTypeGuard.class) {
            if (physicalRestoreDone || physicalRestoreScheduled
                    || physicalRestoreAttempt >= PHYSICAL_RESTORE_DELAYS_MS.length) {
                return;
            }
            delay = PHYSICAL_RESTORE_DELAYS_MS[physicalRestoreAttempt++];
            physicalRestoreScheduled = true;
        }
        WORKER.schedule(() -> {
            synchronized (CardTypeGuard.class) {
                physicalRestoreScheduled = false;
                if (physicalRestoreDone) return;
            }
            // Only while SIM2 is still empty: never touch the modem selection under a present card.
            if (!EuiccCardReader.isModuleActive() || !slot1Key(ctx).isEmpty()) return;
            ToIntFunction<String> restorer = physicalRestorer;
            int rc = restorer != null ? restorer.applyAsInt("sim2_removed") : -1;
            if (rc == 0) {
                synchronized (CardTypeGuard.class) {
                    physicalRestoreDone = true;
                }
            } else {
                schedulePhysicalRestore(ctx);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static void onSim2Present() {
        synchronized (CardTypeGuard.class) {
            physicalRestoreDone = false;
            physicalRestoreAttempt = 0;
        }
    }

    private static void resumeSuspendedEsim(Context ctx, boolean verifiedEuicc) {
        try {
            if (Settings.Secure.getInt(ctx.getContentResolver(), KEY_ESIM_SUSPENDED, 0) != 1) return;
            if (verifiedEuicc) {
                Settings.Secure.putInt(ctx.getContentResolver(), KEY_ENABLE_ESIM, 1);
                Log.i(TAG, "verified eUICC back in SIM2; suspended eSIM switch resumed");
            }
            Settings.Secure.putInt(ctx.getContentResolver(), KEY_ESIM_SUSPENDED, 0);
        } catch (Throwable ignored) {
        }
    }

    static boolean isCarrierBridgeEnabled() {
        return carrierBridgeEnabled;
    }

    static boolean isRewriteTextEnabled() {
        return rewriteTextEnabled;
    }

    static void refreshPrefs(Context ctx) {
        if (ctx == null) return;
        try {
            carrierBridgeEnabled = Settings.Secure.getInt(ctx.getContentResolver(), KEY_CARRIER_BRIDGE, 1) != 0;
        } catch (Throwable ignored) {
            carrierBridgeEnabled = true;
        }
        try {
            rewriteTextEnabled = Settings.Secure.getInt(ctx.getContentResolver(), KEY_REWRITE_TEXT, 1) != 0;
        } catch (Throwable ignored) {
            rewriteTextEnabled = true;
        }
    }

    static boolean setFeaturePref(Context ctx, String key, boolean enabled) {
        if (ctx == null) return false;
        int val = enabled ? 1 : 0;
        boolean ok = Settings.Secure.putInt(ctx.getContentResolver(), key, val);
        if (KEY_CARRIER_BRIDGE.equals(key)) {
            carrierBridgeEnabled = enabled;
        } else if (KEY_REWRITE_TEXT.equals(key)) {
            rewriteTextEnabled = enabled;
        }
        return ok;
    }

    static void reprobeNow(Context ctx) {
        if (ctx == null || !EuiccCardReader.isModuleActive()) return;
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        WORKER.execute(() -> {
            if (!EuiccCardReader.isModuleActive()) return;
            String key = slot1Key(appCtx);
            if (key.isEmpty()) {
                handleEmptySlot1Key(appCtx);
                return;
            }
            synchronized (CardTypeGuard.class) {
                cachedKey = "";
                cachedEuicc = false;
                checkedAt = 0L;
                retryKey = key;
                retryAttempt = 0;
                retryScheduled = false;
                if (key.equals(inFlightKey)) {
                    return;
                }
                inFlightKey = key;
            }
            verifySlot1Card(appCtx, key);
        });
    }

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
                int slot = intent.getIntExtra("android.telephony.extra.SLOT_INDEX",
                        intent.getIntExtra("phone", -1));
                if (slot >= 0 && slot != 1) return;
                if (!EuiccCardReader.isModuleActive()) {
                    synchronized (CardTypeGuard.class) {
                        cachedKey = "";
                        cachedEuicc = false;
                        checkedAt = 0L;
                    }
                    return;
                }

                WORKER.execute(() -> {
                    if (!EuiccCardReader.isModuleActive()) return;
                    String current = slot1Key(appCtx);
                    if (current.isEmpty()) {
                        handleEmptySlot1Key(appCtx);
                        return;
                    }
                    boolean sameVerified;
                    synchronized (CardTypeGuard.class) {
                        unknownStateRetries = 0;
                        sameVerified = current.equals(cachedKey) && cachedEuicc && hasPersistedEuiccState(appCtx);
                        if (!sameVerified) {
                            checkedAt = 0L;
                            if (!current.equals(retryKey)) {
                                retryKey = current;
                                retryAttempt = 0;
                                retryScheduled = false;
                            }
                        }
                    }
                    if (!sameVerified) {
                        Settings.Secure.putInt(appCtx.getContentResolver(), KEY_CARD_TYPE, -1);
                    }
                    if (isEuicc(appCtx)) {
                        Settings.Secure.putInt(appCtx.getContentResolver(), KEY_CARD_TYPE, 1);
                    }
                });
            }
        }, filter, Context.RECEIVER_EXPORTED);

        ContentObserver stateObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                if (!EuiccCardReader.isModuleActive()) {
                    synchronized (CardTypeGuard.class) {
                        cachedKey = "";
                        cachedEuicc = false;
                        checkedAt = 0L;
                    }
                    return;
                }
                WORKER.execute(() -> {
                    if (!EuiccCardReader.isModuleActive()) return;
                    try {
                        int cardType = Settings.Secure.getInt(appCtx.getContentResolver(), KEY_CARD_TYPE, -1);
                        if (cardType == 1 && !hasPersistedEuiccState(appCtx)) {
                            isEuicc(appCtx);
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }
        };
        try {
            appCtx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(KEY_CARD_TYPE), false, stateObserver);
        } catch (Throwable ignored) {
        }

        refreshPrefs(appCtx);
        ContentObserver prefObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                refreshPrefs(appCtx);
            }
        };
        try {
            appCtx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(KEY_CARRIER_BRIDGE), false, prefObserver);
            appCtx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(KEY_REWRITE_TEXT), false, prefObserver);
        } catch (Throwable ignored) {
        }

        if (EuiccCardReader.isModuleActive()) {
            WORKER.execute(() -> isEuicc(appCtx));
        }
    }

    private static int getSlot1SimState(Context ctx) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null) {
                return tm.getSimState(1);
            }
        } catch (Throwable ignored) {
        }
        return TelephonyManager.SIM_STATE_UNKNOWN;
    }

    private static void handleEmptySlot1Key(Context ctx) {
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        int simState = getSlot1SimState(appCtx);
        boolean scheduleUnknownRecheck = false;
        boolean markAbsent = (simState == TelephonyManager.SIM_STATE_ABSENT);
        synchronized (CardTypeGuard.class) {
            cachedKey = "";
            cachedEuicc = false;
            inFlightKey = "";
            checkedAt = 0L;
            retryKey = "";
            retryAttempt = 0;
            retryScheduled = false;
            if (markAbsent) {
                unknownStateRetries = 0;
                unknownStateScheduled = false;
            } else if (!unknownStateScheduled) {
                if (unknownStateRetries < MAX_UNKNOWN_STATE_RETRIES) {
                    unknownStateRetries++;
                    unknownStateScheduled = true;
                    scheduleUnknownRecheck = true;
                } else {
                    markAbsent = true;
                }
            }
        }
        if (markAbsent) {
            Settings.Secure.putInt(appCtx.getContentResolver(), KEY_CARD_TYPE, 0);
            onSim2Removed(appCtx);
            return;
        }
        Settings.Secure.putInt(appCtx.getContentResolver(), KEY_CARD_TYPE, -1);
        if (scheduleUnknownRecheck) {
            WORKER.schedule(() -> {
                synchronized (CardTypeGuard.class) {
                    unknownStateScheduled = false;
                }
                if (!EuiccCardReader.isModuleActive()) return;
                isEuicc(appCtx);
            }, 1500L, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Produces a stable non-empty key whenever physical slot 1 (SIM2) has a card present
     * (including brand-new empty eUICC cards in SIM_STATE_NOT_READY with no ICCID and
     * transitional/empty port lists), and returns "" when SIM2 is absent or unknown.
     */
    static String slot1Key(Context ctx) {
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm == null) {
                return "";
            }
            int simState = tm.getSimState(1);
            if (simState == TelephonyManager.SIM_STATE_ABSENT
                    || simState == TelephonyManager.SIM_STATE_UNKNOWN) {
                return "";
            }
            String physIccid = Sim2Subscription.physicalKey(ctx);
            if (!physIccid.isEmpty()) {
                return physIccid;
            }
            return SLOT1_EMPTY_KEY;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean hasPersistedEuiccState(Context ctx) {
        try {
            if (Settings.Secure.getInt(ctx.getContentResolver(), KEY_CARD_TYPE, -1) != 1) {
                return false;
            }
            String eid = Settings.Secure.getString(ctx.getContentResolver(), KEY_VERIFIED_EID);
            return eid != null && !eid.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasPersistedNormalState(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), KEY_CARD_TYPE, -1) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matchesVerifiedIccid(Context ctx, String key) {
        if (key.isEmpty() || key.startsWith("SLOT1_")) return false;
        try {
            if (!hasPersistedEuiccState(ctx)) {
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
            handleEmptySlot1Key(ctx);
            return false;
        }
        synchronized (CardTypeGuard.class) {
            unknownStateRetries = 0;
            if (key.equals(cachedKey)
                    && (cachedEuicc || (checkedAt > 0L && SystemClock.elapsedRealtime() - checkedAt < 5000L))) {
                if (cachedEuicc ? hasPersistedEuiccState(ctx) : hasPersistedNormalState(ctx)) {
                    return cachedEuicc;
                }
                cachedKey = "";
                cachedEuicc = false;
                checkedAt = 0L;
            }
            if (!cachedEuicc && checkedAt == 0L && matchesVerifiedIccid(ctx, key)) {
                cachedKey = key;
                cachedEuicc = true;
            }
            if (!key.equals(inFlightKey) && !(retryScheduled && key.equals(retryKey))) {
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
        Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        boolean isEuiccResult;
        boolean confirmedNormal = false;
        long token = Binder.clearCallingIdentity();
        try {
            String prevEid = Settings.Secure.getString(ctx.getContentResolver(), KEY_VERIFIED_EID);
            String eidHash = EuiccCardReader.identity(ctx);
            boolean sameEid = prevEid != null && !prevEid.isEmpty() && prevEid.equals(eidHash);
            if (!sameEid) {
                Sim2Subscription.clearPinnedSubId(ctx);
            }
            String physKey = Sim2Subscription.physicalKey(ctx);
            if (!physKey.isEmpty()) {
                Settings.Secure.putString(ctx.getContentResolver(), KEY_VERIFIED_ICCID, EuiccCardReader.hash(physKey));
            } else if (!sameEid) {
                Settings.Secure.putString(ctx.getContentResolver(), KEY_VERIFIED_ICCID, "");
            }
            Settings.Secure.putString(ctx.getContentResolver(), KEY_VERIFIED_EID, eidHash);
            Sim2Subscription.resolveOrNull(ctx);
            isEuiccResult = true;
        } catch (Throwable e) {
            Log.w(TAG, "eUICC check unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            confirmedNormal = "eUICC probe is normal".equals(e.getMessage());
            if (confirmedNormal) {
                Sim2Subscription.clearPinnedSubId(ctx);
            }
            synchronized (CardTypeGuard.class) {
                isEuiccResult = !confirmedNormal && expectedKey.equals(cachedKey) && cachedEuicc;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        String currentKey = slot1Key(ctx);
        if (!expectedKey.equals(currentKey)) {
            synchronized (CardTypeGuard.class) {
                if (expectedKey.equals(inFlightKey)) {
                    inFlightKey = "";
                }
            }
            if (EuiccCardReader.isModuleActive()) {
                WORKER.execute(() -> isEuicc(appCtx));
            }
            return false;
        }

        if (!isEuiccResult && !confirmedNormal) {
            long retryDelayMs = -1L;
            int attemptNum = 0;
            synchronized (CardTypeGuard.class) {
                inFlightKey = "";
                if (!expectedKey.equals(retryKey)) {
                    retryKey = expectedKey;
                    retryAttempt = 0;
                    retryScheduled = false;
                }
                if (retryAttempt < RETRY_DELAYS_MS.length) {
                    retryDelayMs = RETRY_DELAYS_MS[retryAttempt++];
                    attemptNum = retryAttempt;
                    cachedKey = "";
                    cachedEuicc = false;
                    checkedAt = 0L;
                    retryScheduled = true;
                } else {
                    cachedKey = expectedKey;
                    cachedEuicc = false;
                    checkedAt = SystemClock.elapsedRealtime();
                    retryScheduled = false;
                }
            }
            if (retryDelayMs > 0L) {
                Settings.Secure.putInt(ctx.getContentResolver(), KEY_CARD_TYPE, -1);
                Log.i(TAG, "SIM2 probe transient unknown; scheduling retry " + attemptNum
                        + "/" + RETRY_DELAYS_MS.length + " in " + retryDelayMs + "ms");
                WORKER.schedule(() -> {
                    boolean shouldVerify = false;
                    synchronized (CardTypeGuard.class) {
                        if (expectedKey.equals(retryKey)) {
                            retryScheduled = false;
                        }
                        if (EuiccCardReader.isModuleActive()
                                && expectedKey.equals(slot1Key(appCtx))
                                && !(expectedKey.equals(cachedKey) && cachedEuicc)
                                && !expectedKey.equals(inFlightKey)) {
                            inFlightKey = expectedKey;
                            shouldVerify = true;
                        }
                    }
                    if (shouldVerify) {
                        verifySlot1Card(appCtx, expectedKey);
                    }
                }, retryDelayMs, TimeUnit.MILLISECONDS);
                return false;
            }
        } else {
            synchronized (CardTypeGuard.class) {
                cachedKey = expectedKey;
                cachedEuicc = isEuiccResult;
                checkedAt = SystemClock.elapsedRealtime();
                inFlightKey = "";
                retryKey = "";
                retryAttempt = 0;
                retryScheduled = false;
            }
        }

        onSim2Present();
        if (isEuiccResult || confirmedNormal) {
            resumeSuspendedEsim(ctx, isEuiccResult);
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
