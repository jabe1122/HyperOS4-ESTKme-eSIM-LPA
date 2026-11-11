package io.github.local.esimh4probe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.se.omapi.Channel;
import android.se.omapi.Reader;
import android.se.omapi.SEService;
import android.se.omapi.Session;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.euicc.DownloadableSubscription;
import android.util.Log;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * LSPosed compatibility module for Xiaomi HyperOS 4 devices (e.g. MIX Flip 2 / bixi,
 * REDMI K90 Pro Max / myron, Android 15–17 / SDK 35+) with a removable eUICC
 * (ESTKme, EasyEUICC / 小白卡 / JoyTel / 9eSIM / Xesim / 5ber) in physical Slot 2 (SIM2).
 *
 * Isolates OEM soldered-eSIM GPIO/radio mux switching in com.android.phone, bridges SIM2's
 * native or synthetic eUICC cardId cleanly across single-port and MEP-configured ROMs,
 * supports empty / all-profiles-disabled cards, and adapts Xiaomi MIUIEsimLPA to communicate
 * with SIM2 via dual-channel OMAPI + RIL with ISO-7816-4 61XX/6CXX APDU handling.
 */
public final class CompatModule extends XposedModule {
    private static final String TAG = "EsimH4Compat";
    private static final int MIN_SDK = 35;
    private static final String MOCK_DOMAIN = "test.esim.local";

    private static final ThreadLocal<Boolean> IN_GET_CARDS = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> IN_DIRECT_RIL = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> IN_RAW_PORT_CHECK = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Method CURRENT_APP_METHOD;

    static {
        Method m = null;
        try {
            m = Class.forName("android.app.ActivityThread").getMethod("currentApplication");
        } catch (Throwable ignored) {
        }
        CURRENT_APP_METHOD = m;
    }

    private boolean phoneInstalled = false;
    private boolean lpaInstalled = false;

    private static boolean active() {
        return EuiccCardReader.isModuleActive();
    }

    private static Application currentApp() {
        if (CURRENT_APP_METHOD == null) return null;
        try {
            return (Application) CURRENT_APP_METHOD.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context resolvePhoneContext(Field mContextField) {
        if (mContextField != null) {
            try {
                Context ctx = (Context) mContextField.get(null);
                if (ctx != null) return ctx;
            } catch (Throwable ignored) {
            }
        }
        return currentApp();
    }

    private static String stripSpaces(String s) {
        int len = s.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u00A0') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static CharSequence rewriteSim2EsimText(CharSequence original) {
        if (original == null) return null;
        String text = original.toString();
        if (text.indexOf("eSIM") < 0 && text.indexOf("ESIM") < 0) {
            return original;
        }
        String compact = stripSpaces(text);
        if ("打开后eSIM将替换SIM卡2".equals(compact)) {
            return "与卡槽2中的eSIM卡同步开启或关闭";
        }
        if (compact.contains("关闭eSIM并切换至SIM卡2")) {
            return "关闭eSIM将同时停用卡槽2中的eSIM卡。是否关闭？";
        }
        if (compact.contains("开启eSIM") && compact.contains("SIM卡2")
                && (compact.contains("关闭") || compact.contains("不可用") || compact.contains("停用"))) {
            return "开启eSIM将同时启用卡槽2中的eSIM卡。是否开启？";
        }
        return original;
    }

    private static int resolveSyntheticSlot1CardId(Iterable<?> cards) {
        int candidate = 1;
        if (cards == null) return candidate;
        while (true) {
            boolean collision = false;
            for (Object item : cards) {
                if (item instanceof UiccCardInfo) {
                    UiccCardInfo c = (UiccCardInfo) item;
                    if (c.getPhysicalSlotIndex() != 1 && c.getCardId() == candidate) {
                        collision = true;
                        break;
                    }
                }
            }
            if (!collision) return candidate;
            candidate++;
        }
    }

    private static int resolveSlot1CardId(Context ctx) {
        if (ctx == null) return 1;
        boolean prevInGetCards = IN_GET_CARDS.get();
        IN_GET_CARDS.set(true);
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null) {
                List<UiccCardInfo> cards = tm.getUiccCardsInfo();
                if (cards != null) {
                    for (UiccCardInfo card : cards) {
                        if (card != null && card.getPhysicalSlotIndex() == 1) {
                            int nativeId = card.getCardId();
                            return nativeId >= 0 ? nativeId : resolveSyntheticSlot1CardId(cards);
                        }
                    }
                    return resolveSyntheticSlot1CardId(cards);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            IN_GET_CARDS.set(prevInGetCards);
        }
        return 1;
    }

    private static boolean isCompatibleEnvironment(PackageReadyParam param) {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            Log.w(TAG, "system SDK " + Build.VERSION.SDK_INT + " below minimum supported " + MIN_SDK + ", bypassing hook");
            return false;
        }
        String pkg = param.getPackageName();
        ClassLoader cl = param.getClassLoader();
        try {
            if ("com.android.phone".equals(pkg)) {
                cl.loadClass("com.android.phone.MiuiEsimManagerBase");
                return true;
            } else if ("com.miui.euicc".equals(pkg)) {
                cl.loadClass("com.miui.euicc.services.XmLPAService");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "dynamic target detection skipped for " + pkg + ": " + t.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        Log.i(TAG, "package ready: " + pkg + "; device=" + Build.DEVICE + "; incremental=" + Build.VERSION.INCREMENTAL
                + "; sdk=" + Build.VERSION.SDK_INT + "; active=" + active());
        if (!isCompatibleEnvironment(param)) {
            return;
        }
        try {
            if ("com.miui.euicc".equals(pkg) && !lpaInstalled) {
                installLpaHooks(param.getClassLoader());
                lpaInstalled = true;
            } else if ("com.android.phone".equals(pkg) && !phoneInstalled) {
                installPhoneHooks(param.getClassLoader());
                phoneInstalled = true;
            } else if (!phoneInstalled && !lpaInstalled) {
                // Fallback if com.android.phone process started while LSPosed was reloading module scope
                // and only notified a co-hosted package in android.uid.phone.
                Application app = currentApp();
                if (app != null && "com.android.phone".equals(app.getPackageName())) {
                    installPhoneHooks(app.getClassLoader());
                    phoneInstalled = true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "compatibility setup failed for " + pkg, t);
        }
    }

    private void installPhoneHooks(ClassLoader loader) throws Exception {
        Class<?> baseClass = loader.loadClass("com.android.phone.MiuiEsimManagerBase");
        Field mContextField = null;
        try {
            mContextField = baseClass.getDeclaredField("mContext");
            mContextField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        final Field phoneCtxField = mContextField;

        hook(Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class)).intercept(chain -> {
            Object result = chain.proceed();
            Application app = (Application) chain.getArg(0);
            if (app != null && "com.android.phone".equals(app.getPackageName())) {
                try {
                    CardTypeGuard.start(app);
                } catch (Throwable e) {
                    Log.e(TAG, "SIM2 observer unavailable", e);
                }
            }
            return result;
        });

        Application existingApp = currentApp();
        if (existingApp != null && "com.android.phone".equals(existingApp.getPackageName())) {
            try {
                CardTypeGuard.start(existingApp);
            } catch (Throwable ignored) {
            }
        }

        hook(Resources.class.getDeclaredMethod("getText", int.class)).intercept(chain -> {
            Object original = chain.proceed();
            if (!active() || !(original instanceof CharSequence)) return original;
            return rewriteSim2EsimText((CharSequence) original);
        });

        try {
            hook(Resources.class.getDeclaredMethod("getText", int.class, CharSequence.class)).intercept(chain -> {
                Object original = chain.proceed();
                if (!active() || !(original instanceof CharSequence)) return original;
                return rewriteSim2EsimText((CharSequence) original);
            });
        } catch (Throwable ignored) {
        }

        Set<Method> hookedMethods = new HashSet<>();
        String[] esimClasses = {
            "MiuiEsimManagerBase",
            "MiuiEsimManager",
            "QcMiuiEsimManager",
            "QcMiuiEsimManagerEx"
        };
        for (String simpleName : esimClasses) {
            Class<?> type;
            try {
                type = loader.loadClass("com.android.phone." + simpleName);
            } catch (ClassNotFoundException e) {
                Log.d(TAG, "optional manager class not present: " + simpleName);
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers()) || !hookedMethods.add(method)) {
                    continue;
                }
                String mName = method.getName();
                if ("recoveryForInit".equals(mName) && method.getParameterCount() == 0) {
                    hook(method).intercept(chain -> active() ? null : chain.proceed());
                } else if (("isEsimActive".equals(mName) || "isSupportEsim".equals(mName) || "isEsimEnabled".equals(mName))
                        && method.getParameterCount() == 0 && method.getReturnType() == boolean.class) {
                    final boolean checkActiveState = "isEsimActive".equals(mName);
                    hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();
                        Context ctx = resolvePhoneContext(phoneCtxField);
                        if (ctx == null || !CardTypeGuard.isEuicc(ctx)) return false;
                        if (!checkActiveState) return true;
                        TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
                        int simState = tm != null ? tm.getSimState(1) : TelephonyManager.SIM_STATE_UNKNOWN;
                        if (simState == TelephonyManager.SIM_STATE_ABSENT
                                || simState == TelephonyManager.SIM_STATE_UNKNOWN) {
                            return false;
                        }
                        if (Settings.Secure.getInt(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM, 0) != 1) {
                            return false;
                        }
                        if (simState == TelephonyManager.SIM_STATE_READY) {
                            return true;
                        }
                        // Brand-new empty eUICC (e.g. 小白卡) or eUICC with all SGP.22 profiles disabled may stay
                        // in SIM_STATE_NOT_READY without an active profile; keep eSIM logically active when
                        // enabled so Settings keeps "Manage eSIM" clickable for downloading the first profile.
                        SubscriptionInfo subInfo = Sim2Subscription.resolveOrNull(ctx);
                        return subInfo == null || Sim2Subscription.enabledSafe(subInfo);
                    });
                } else if ("setEsimState".equals(mName)
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == int.class
                        && method.getReturnType() == int.class) {
                    hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();
                        int state = (int) chain.getArg(0);
                        if (state != 0 && state != 1) return chain.proceed();
                        Context ctx = resolvePhoneContext(phoneCtxField);
                        if (ctx == null) return -1;
                        long token = Binder.clearCallingIdentity();
                        try {
                            if (!CardTypeGuard.isEuiccForSwitch(ctx)) {
                                if (state == 1) {
                                    Settings.Secure.putInt(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM, 0);
                                    return 0;
                                }
                                Log.w(TAG, "eSIM switch rejected: no verified eUICC in SIM2; ordinary SIM left unchanged");
                                return -1;
                            }
                            SubscriptionInfo subInfo = Sim2Subscription.resolveOrNull(ctx);
                            String prevSetting = Settings.Secure.getString(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM);
                            boolean enableSim2 = (state == 0);
                            if (!Settings.Secure.putInt(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM, enableSim2 ? 1 : 0)) {
                                return -1;
                            }
                            if (subInfo != null) {
                                try {
                                    Sim2Subscription.set(ctx, subInfo, enableSim2);
                                } catch (Throwable t) {
                                    Settings.Secure.putString(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM, prevSetting);
                                    Log.e(TAG, "SIM2 switch failed; logical state restored", t);
                                    return -1;
                                }
                                Log.i(TAG, "eSIM switch=" + enableSim2 + "; SIM2 UICC applications=" + enableSim2);
                            } else {
                                Log.i(TAG, "eSIM switch=" + enableSim2 + "; verified SIM2 eUICC has no active profile yet (logical state updated)");
                            }
                            return 0;
                        } catch (Throwable t) {
                            Log.e(TAG, "eSIM/SIM2 switch rejected", t);
                            return -1;
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    });
                } else if ("getEsimGPIOState".equals(mName) && method.getParameterCount() == 0) {
                    hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();
                        Context ctx = resolvePhoneContext(phoneCtxField);
                        return ctx != null && Settings.Secure.getInt(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM, 0) == 1 ? 0 : 1;
                    });
                }
            }
        }

        Class<?> uiccCtrl = loader.loadClass("com.android.internal.telephony.uicc.UiccController");
        hook(uiccCtrl.getDeclaredMethod("getCardIdForDefaultEuicc")).intercept(chain -> {
            Object orig = chain.proceed();
            if (!active()) return orig;
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = resolvePhoneContext(phoneCtxField);
                if (ctx != null && CardTypeGuard.isEuicc(ctx)) {
                    return resolveSlot1CardId(ctx);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return orig;
        });

        Field fIsEuicc = UiccCardInfo.class.getDeclaredField("mIsEuicc");
        fIsEuicc.setAccessible(true);
        Field fCardId = UiccCardInfo.class.getDeclaredField("mCardId");
        fCardId.setAccessible(true);

        hook(uiccCtrl.getDeclaredMethod("getAllUiccCardInfos")).intercept(chain -> {
            Object orig = chain.proceed();
            if (!active() || IN_GET_CARDS.get() || !(orig instanceof ArrayList)) return orig;
            IN_GET_CARDS.set(true);
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = resolvePhoneContext(phoneCtxField);
                if (ctx == null || !CardTypeGuard.isEuicc(ctx)) return orig;
                ArrayList<?> list = (ArrayList<?>) orig;
                int syntheticCardId = resolveSyntheticSlot1CardId(list);
                for (Object item : list) {
                    if (item instanceof UiccCardInfo) {
                        UiccCardInfo card = (UiccCardInfo) item;
                        if (card.getPhysicalSlotIndex() == 1) {
                            fIsEuicc.setBoolean(card, true);
                            if (card.getCardId() < 0) {
                                fCardId.setInt(card, syntheticCardId);
                            }
                        }
                    }
                }
                return orig;
            } catch (Throwable t) {
                Log.w(TAG, "UiccCardInfo eUICC decoration skipped: " + t.getClass().getSimpleName());
                return orig;
            } finally {
                Binder.restoreCallingIdentity(token);
                IN_GET_CARDS.set(false);
            }
        });

        try {
            Class<?> uiDisp = loader.loadClass("com.android.phone.euicc.EuiccUiDispatcherActivity");
            Method resolveUi = uiDisp.getDeclaredMethod("resolveEuiccUiIntent");
            resolveUi.setAccessible(true);
            hook(resolveUi).intercept(chain -> {
                Object orig = chain.proceed();
                if (!active() || orig != null) return orig;
                long token = Binder.clearCallingIdentity();
                try {
                    Context ctx = resolvePhoneContext(phoneCtxField);
                    if (ctx == null || !CardTypeGuard.isEuicc(ctx)) return null;
                    Activity act = (Activity) chain.getThisObject();
                    Intent incoming = act != null ? act.getIntent() : null;
                    if (incoming != null && "android.telephony.euicc.action.START_EUICC_ACTIVATION".equals(incoming.getAction())) {
                        Intent bridged = new Intent();
                        bridged.setComponent(new ComponentName("com.miui.euicc", "com.miui.euicc.ui.active.CaptureActivity"));
                        return bridged;
                    }
                } catch (Throwable ignored) {
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                return null;
            });
        } catch (Throwable t) {
            Log.w(TAG, "EuiccUiDispatcherActivity hook skipped: " + t.getClass().getSimpleName());
        }

        Class<?> euiccCtrl = loader.loadClass("com.android.internal.telephony.euicc.EuiccController");
        Method getSlotIdx = euiccCtrl.getDeclaredMethod("getSlotIndexFromCardId", int.class);
        getSlotIdx.setAccessible(true);
        hook(getSlotIdx).intercept(chain -> {
            Object orig = chain.proceed();
            if (!active()) return orig;
            int requestedCardId = (int) chain.getArg(0);
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = resolvePhoneContext(phoneCtxField);
                if (ctx != null && CardTypeGuard.isEuicc(ctx)) {
                    int slot1CardId = resolveSlot1CardId(ctx);
                    if (requestedCardId == slot1CardId || requestedCardId < 0
                            || !(orig instanceof Integer) || (int) orig < 0) {
                        return 1;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return orig;
        });

        Class<?> euiccConn = loader.loadClass("com.android.internal.telephony.euicc.EuiccConnector");
        Method getSlotId = euiccConn.getDeclaredMethod("getSlotIdFromCardId", int.class);
        getSlotId.setAccessible(true);
        hook(getSlotId).intercept(chain -> {
            Object orig = chain.proceed();
            if (!active()) return orig;
            int requestedCardId = (int) chain.getArg(0);
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = resolvePhoneContext(phoneCtxField);
                if (ctx != null && CardTypeGuard.isEuicc(ctx)) {
                    int slot1CardId = resolveSlot1CardId(ctx);
                    if (requestedCardId == slot1CardId || requestedCardId < 0
                            || !(orig instanceof Integer) || (int) orig < 0) {
                        return 1;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return orig;
        });

        Method checkMeta = euiccCtrl.getDeclaredMethod("checkCarrierPrivilegeInMetadata", DownloadableSubscription.class, String.class);
        hook(checkMeta).intercept(chain -> {
            Object orig = chain.proceed();
            if (!active() || Boolean.TRUE.equals(orig)) return orig;
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = resolvePhoneContext(phoneCtxField);
                if (ctx != null && CardTypeGuard.isEuicc(ctx)) return true;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return orig;
        });

        Method sendRes = euiccCtrl.getDeclaredMethod("sendResult", PendingIntent.class, int.class, Intent.class);
        sendRes.setAccessible(true);
        for (Method m : euiccCtrl.getDeclaredMethods()) {
            if ("downloadSubscriptionPrivileged".equals(m.getName()) && m.getParameterCount() == 11) {
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    if (!active()) return chain.proceed();
                    DownloadableSubscription sub = (DownloadableSubscription) chain.getArg(3);
                    String code = sub != null ? sub.getEncodedActivationCode() : null;
                    if (code != null && code.contains(MOCK_DOMAIN)) {
                        PendingIntent pi = (PendingIntent) chain.getArg(8);
                        Intent res = new Intent();
                        res.putExtra("android.telephony.euicc.extra.EMBEDDED_SUBSCRIPTION_DOWNLOADABLE_SUBSCRIPTION", sub);
                        res.putExtra("io.github.local.esimh4.MOCK_INTERCEPTED", true);
                        Log.i(TAG, "Mock eSIM download safely intercepted after user consent (" + MOCK_DOMAIN + ")");
                        sendRes.invoke(chain.getThisObject(), pi, 0, res);
                        return null;
                    }
                    return chain.proceed();
                });
            }
        }

        Log.i(TAG, "phone hooks ready; OEM GPIO/radio switching isolated; eUICC cardId bridge active");
    }

    private void installLpaHooks(ClassLoader loader) throws Exception {
        Class<?> providerClass = loader.loadClass("com.miui.euicc.provider.XMEuiccProvider");
        Class<?> simUtilClass = loader.loadClass("com.miui.euicc.profileassistant.util.SimCardUtil");
        Method getSimUtilInst = simUtilClass.getDeclaredMethod("h");
        Method getPortsMethod = simUtilClass.getDeclaredMethod("g", Context.class);
        Method isPortActiveMethod = simUtilClass.getDeclaredMethod("t", Context.class, int.class);
        Method syncNickSubInfoMethod = simUtilClass.getDeclaredMethod("y", String.class, String.class);

        hook(providerClass.getDeclaredMethod("call", String.class, String.class, Bundle.class)).intercept(chain -> {
            if (!EuiccCardReader.PROBE_METHOD.equals(chain.getArg(0))) {
                return chain.proceed();
            }
            Bundle result = new Bundle();
            int uid = Binder.getCallingUid();
            if (uid != 0 && uid != 1001) {
                result.putString("status", "denied");
                return result;
            }
            if (!active()) {
                result.putString("status", "disabled");
                return result;
            }
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = ((ContentProvider) chain.getThisObject()).getContext();
                OmapiBridge.setContext(ctx);
                result.putString("eid_hash", EuiccCardReader.hash(OmapiBridge.readEid(true)));
                result.putString("status", "euicc");
                if (uid == 0 && "diagnostics".equals(chain.getArg(1))) {
                    Object utilInst = getSimUtilInst.invoke(null);
                    List<?> ports = (List<?>) getPortsMethod.invoke(utilInst, ctx);
                    result.putInt("lpa_port_count", ports != null ? ports.size() : 0);
                    IN_RAW_PORT_CHECK.set(true);
                    try {
                        result.putBoolean("lpa_port0_active", (boolean) isPortActiveMethod.invoke(utilInst, ctx, 0));
                    } finally {
                        IN_RAW_PORT_CHECK.set(false);
                    }
                }
            } catch (Throwable e) {
                e = EuiccCardReader.unwrap(e);
                result.putString("status", (!OmapiBridge.isBusy() && e instanceof NoSuchElementException) ? "normal" : "unknown");
                Log.i(TAG, "read-only SIM2 probe: " + e.getClass().getSimpleName());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return result;
        });

        hook(getPortsMethod).intercept(chain -> {
            if (!active()) return chain.proceed();
            Context ctx = (Context) chain.getArg(0);
            OmapiBridge.setContext(ctx);
            try {
                OmapiBridge.readEid(false);
                TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
                if (tm != null) {
                    List<UiccCardInfo> cards = tm.getUiccCardsInfo();
                    if (cards != null) {
                        for (UiccCardInfo card : cards) {
                            if (card != null && card.getPhysicalSlotIndex() == 1 && card.getPorts() != null) {
                                return new ArrayList<>(card.getPorts());
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "verified SIM2 ports unavailable: " + e.getClass().getSimpleName());
            }
            return new ArrayList<>();
        });

        hook(isPortActiveMethod).intercept(chain -> {
            Object orig = chain.proceed();
            if (!active() || IN_RAW_PORT_CHECK.get() || Boolean.TRUE.equals(orig)) return orig;
            int portIdx = (int) chain.getArg(1);
            if (portIdx != 0) return orig;
            Context ctx = (Context) chain.getArg(0);
            if (ctx == null) return orig;
            OmapiBridge.setContext(ctx);
            try {
                TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
                if (tm != null) {
                    int simState = tm.getSimState(1);
                    if (simState != TelephonyManager.SIM_STATE_ABSENT
                            && simState != TelephonyManager.SIM_STATE_UNKNOWN) {
                        OmapiBridge.readEid(false);
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            return orig;
        });

        // On HyperOS 4 devices whose stock vendor ROM declares android.hardware.telephony.euicc.mep
        // (such as REDMI K90 Pro Max / myron), keep MIUIEsimLPA in SEP (port 0, logical slot 1, direct
        // ISD-R APDU) mode whenever our removable SIM2 eUICC module is active so LPA never routes ES10x
        // jobs into frameworkEs10xExecute (v3.b.d) or attempts ESIM_DUAL / port-1 slot remapping (g4.a.j).
        try {
            Class<?> appPmClass = Class.forName("android.app.ApplicationPackageManager");
            hook(appPmClass.getDeclaredMethod("hasSystemFeature", String.class)).intercept(chain -> {
                if (active() && "android.hardware.telephony.euicc.mep".equals(chain.getArg(0))) {
                    return false;
                }
                return chain.proceed();
            });
            try {
                hook(appPmClass.getDeclaredMethod("hasSystemFeature", String.class, int.class)).intercept(chain -> {
                    if (active() && "android.hardware.telephony.euicc.mep".equals(chain.getArg(0))) {
                        return false;
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "LPA ApplicationPackageManager MEP feature hook skipped: " + t.getClass().getSimpleName());
        }

        try {
            Class<?> mepHelperClass = loader.loadClass("g4.a");
            hook(mepHelperClass.getDeclaredMethod("j", Context.class)).intercept(chain ->
                    active() ? Boolean.FALSE : chain.proceed());
        } catch (Throwable t) {
            Log.w(TAG, "LPA g4.a MEP guard hook skipped: " + t.getClass().getSimpleName());
        }

        try {
            Class<?> devUtilClass = loader.loadClass("v3.b");
            hook(devUtilClass.getDeclaredMethod("b")).intercept(chain ->
                    active() ? Boolean.FALSE : chain.proceed());
            hook(devUtilClass.getDeclaredMethod("d")).intercept(chain ->
                    active() ? Boolean.FALSE : chain.proceed());
        } catch (Throwable t) {
            Log.w(TAG, "LPA v3.b MEP guard hook skipped: " + t.getClass().getSimpleName());
        }

        Method resolvedSaveEidMethod = null;
        try {
            Class<?> q4b = loader.loadClass("q4.b");
            Method saveMethod = q4b.getDeclaredMethod("q", String.class);
            saveMethod.setAccessible(true);
            resolvedSaveEidMethod = saveMethod;

            Class<?> h3n = loader.loadClass("h3.n");
            Method showEidDialogMethod = h3n.getDeclaredMethod("r", Context.class, String.class);
            showEidDialogMethod.setAccessible(true);

            final Method finalSaveMethod = resolvedSaveEidMethod;
            hook(h3n.getDeclaredMethod("k", Context.class)).intercept(chain -> {
                if (!active()) return chain.proceed();
                Context ctx = (Context) chain.getArg(0);
                OmapiBridge.setContext(ctx);
                try {
                    String eid = OmapiBridge.readEid(false);
                    if (finalSaveMethod != null) {
                        try {
                            finalSaveMethod.invoke(null, eid);
                        } catch (Throwable ignored) {
                        }
                    }
                    return showEidDialogMethod.invoke(null, ctx, eid);
                } catch (Throwable e) {
                    if (finalSaveMethod != null) {
                        try {
                            finalSaveMethod.invoke(null, new Object[]{null});
                        } catch (Throwable ignored) {
                        }
                    }
                    Toast.makeText(ctx, "卡槽2未检测到可用eSIM卡，无法读取EID", Toast.LENGTH_LONG).show();
                    return null;
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "LPA EID dialog hook skipped (obfuscated class mismatch: q4.b/h3.n): " + t.getMessage());
        }
        final Method saveEidMethod = resolvedSaveEidMethod;

        hook(simUtilClass.getDeclaredMethod("e", Context.class, int.class)).intercept(chain -> {
            int slotArg = (int) chain.getArg(1);
            if (!active() || (slotArg != 1 && slotArg >= 0)) return chain.proceed();
            Context ctx = (Context) chain.getArg(0);
            OmapiBridge.setContext(ctx);
            try {
                String eid = OmapiBridge.readEid(false);
                if (saveEidMethod != null) {
                    try {
                        saveEidMethod.invoke(null, eid);
                    } catch (Throwable ignored) {
                    }
                }
                Log.i(TAG, "settings EID reader used real SIM2 EID (value omitted)");
                return eid;
            } catch (Throwable e) {
                if (saveEidMethod != null) {
                    try {
                        saveEidMethod.invoke(null, new Object[]{null});
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }
        });

        Class<?> serviceClass = loader.loadClass("com.miui.euicc.services.XmLPAService");
        hook(serviceClass.getDeclaredMethod("onGetEid", int.class)).intercept(chain -> {
            int slot = (int) chain.getArg(0);
            if (!active() || (slot != 1 && slot >= 0)) return chain.proceed();
            try {
                String eid = OmapiBridge.readEid(false);
                if (saveEidMethod != null) {
                    try {
                        saveEidMethod.invoke(null, eid);
                    } catch (Throwable ignored) {
                    }
                }
                return eid;
            } catch (Throwable e) {
                if (saveEidMethod != null) {
                    try {
                        saveEidMethod.invoke(null, new Object[]{null});
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }
        });

        Constructor<?> metaResultCtor = Class.forName("android.service.euicc.GetDownloadableSubscriptionMetadataResult")
                .getConstructor(int.class, DownloadableSubscription.class);
        Constructor<?> dlResultCtor = Class.forName("android.service.euicc.DownloadSubscriptionResult")
                .getConstructor(int.class, int.class, int.class);

        for (Method m : serviceClass.getDeclaredMethods()) {
            if ("onGetDownloadableSubscriptionMetadata".equals(m.getName())) {
                hook(m).intercept(chain -> {
                    if (!active()) return chain.proceed();
                    for (Object arg : chain.getArgs()) {
                        if (arg instanceof DownloadableSubscription) {
                            DownloadableSubscription sub = (DownloadableSubscription) arg;
                            String code = sub.getEncodedActivationCode();
                            if (code != null && !code.isEmpty()) {
                                return metaResultCtor.newInstance(0, sub);
                            }
                            break;
                        }
                    }
                    return chain.proceed();
                });
            } else if ("onDownloadSubscription".equals(m.getName())) {
                final boolean returnsInt = (m.getReturnType() == int.class);
                hook(m).intercept(chain -> {
                    if (!active()) return chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    DownloadableSubscription sub = null;
                    int subIdx = -1;
                    for (int idx = 0; idx < args.length; idx++) {
                        if (args[idx] instanceof DownloadableSubscription) {
                            sub = (DownloadableSubscription) args[idx];
                            subIdx = idx;
                            break;
                        }
                    }
                    String code = sub != null ? sub.getEncodedActivationCode() : null;
                    if (code != null && code.contains(MOCK_DOMAIN)) {
                        return returnsInt ? 0 : dlResultCtor.newInstance(0, 0, -1);
                    }
                    if (code != null && code.regionMatches(true, 0, "LPA:", 0, 4) && subIdx >= 0) {
                        DownloadableSubscription.Builder builder = new DownloadableSubscription.Builder(code.substring(4));
                        builder.setConfirmationCode(sub.getConfirmationCode());
                        args[subIdx] = builder.build();
                    }
                    // XmLPAService.onDownloadSubscription (4-arg, 5-arg, and 6-arg overloads) always takes
                    // slotIndex/slotId (1 for SIM2) as arg[0], never cardId.
                    if (args.length > 0 && args[0] instanceof Integer) {
                        args[0] = 1;
                    }
                    if (args.length == 6 && args[1] instanceof Integer && (int) args[1] < 0) {
                        args[1] = 0;
                    }
                    return chain.proceed(args);
                });
            }
        }

        Method openChMethod = TelephonyManager.class.getDeclaredMethod("iccOpenLogicalChannelBySlot", int.class, String.class, int.class);
        Method closeChMethod = TelephonyManager.class.getDeclaredMethod("iccCloseLogicalChannelBySlot", int.class, int.class);
        Method transmitApduMethod = TelephonyManager.class.getDeclaredMethod("iccTransmitApduLogicalChannelBySlot",
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, String.class);

        hook(openChMethod).intercept(chain -> {
            if (!active() || IN_DIRECT_RIL.get() || (int) chain.getArg(0) != 1) return chain.proceed();
            Object result = null;
            try {
                result = chain.proceed();
            } catch (Throwable rilOpenErr) {
                Log.w(TAG, "RIL open channel threw " + rilOpenErr.getClass().getSimpleName() + "; falling back to OMAPI");
            }
            if (result != null) {
                int status = OmapiBridge.getResponseStatus(result);
                if (status == EuiccCardReader.RIL_STATUS_NO_ERROR) {
                    int chId = OmapiBridge.getResponseChannel(result);
                    if (chId >= 0) {
                        OmapiBridge.onRilOpen(chId);
                        return result;
                    }
                } else if (status == EuiccCardReader.RIL_STATUS_NO_SUCH_ELEMENT) {
                    return result;
                }
            }
            return OmapiBridge.openSafe((String) chain.getArg(1), (int) chain.getArg(2), result);
        });

        final boolean closeReturnsBoolean = (closeChMethod.getReturnType() == boolean.class);
        hook(closeChMethod).intercept(chain -> {
            if (!active() || IN_DIRECT_RIL.get()) return chain.proceed();
            int slot = (int) chain.getArg(0);
            if (slot != 1) {
                return chain.proceed();
            }
            int id = (int) chain.getArg(1);
            if (!OmapiBridge.owns(id)) {
                OmapiBridge.onRilClose(id);
                return chain.proceed();
            }
            OmapiBridge.close(id);
            return closeReturnsBoolean ? Boolean.TRUE : null;
        });

        hook(transmitApduMethod).intercept(chain -> {
            if (!active() || IN_DIRECT_RIL.get() || (int) chain.getArg(0) != 1) return chain.proceed();
            int id = (int) chain.getArg(1);
            int cla = (int) chain.getArg(2);
            int ins = (int) chain.getArg(3);
            int p1 = (int) chain.getArg(4);
            int p2 = (int) chain.getArg(5);
            int p3 = (int) chain.getArg(6);
            String data = (String) chain.getArg(7);
            if (OmapiBridge.owns(id)) {
                return OmapiBridge.transmit(id, cla, ins, p1, p2, p3, data);
            }
            boolean isCase4 = p3 > 0 && data != null && data.length() == (p3 + 1) * 2;
            String effectiveData = data;
            Object rawObj = null;
            Throwable proceedErr = null;
            try {
                rawObj = chain.proceed();
            } catch (Throwable t) {
                if (!isCase4) {
                    throw t;
                }
                proceedErr = t;
            }
            String rawStr = (rawObj instanceof String) ? ((String) rawObj).trim() : null;
            if (isCase4 && (rawStr == null || rawStr.length() < 4
                    || "6700".equalsIgnoreCase(rawStr) || "6F00".equalsIgnoreCase(rawStr))) {
                String case3Data = data.substring(0, p3 * 2);
                TelephonyManager tm = (TelephonyManager) chain.getThisObject();
                IN_DIRECT_RIL.set(true);
                try {
                    rawStr = EuiccCardReader.invokeRilTx(tm, 1, id, cla, ins, p1, p2, p3, case3Data);
                    rawObj = rawStr;
                    effectiveData = case3Data;
                    proceedErr = null;
                } finally {
                    IN_DIRECT_RIL.set(false);
                }
            }
            if (proceedErr != null) {
                throw proceedErr;
            }
            if (!(rawObj instanceof String) || (ins & 0xFF) == 0xC0) {
                return rawObj;
            }
            return OmapiBridge.completeRilApduIfNeeded(
                    (TelephonyManager) chain.getThisObject(), id, cla, ins, p1, p2, p3, effectiveData, (String) rawObj);
        });

        try {
            Class<?> dpOpClass = loader.loadClass("com.miui.euicc.handlers.DpDownloadOperation");
            Field fCode = dpOpClass.getDeclaredField("i");
            fCode.setAccessible(true);
            Method getAppCtx = loader.loadClass("com.miui.euicc.LpaApplication").getDeclaredMethod("b");
            hook(dpOpClass.getDeclaredMethod("h")).intercept(chain -> {
                if (!active()) return chain.proceed();
                Object self = chain.getThisObject();
                String code = self != null ? (String) fCode.get(self) : null;
                if (code != null && code.contains(MOCK_DOMAIN)) {
                    Log.i(TAG, "Mock LPA URI download safely intercepted in DpDownloadOperation (" + MOCK_DOMAIN + ")");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            Context appCtx = (Context) getAppCtx.invoke(null);
                            if (appCtx != null) {
                                Toast.makeText(appCtx, "【安全拦截】小米写卡页已成功接收模拟 LPA 激活码，未向实体卡写入测试数据", Toast.LENGTH_LONG).show();
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            Log.w(TAG, "DpDownloadOperation mock guard skipped: " + t.getClass().getSimpleName());
        }

        try {
            // Adapt MIUIEsimLPA v4.1.0.1 ProfileManageFragment$f.b(String) nickname editing for removable SIM2 eUICC:
            // Always send the SGP.22 SetNickname APDU via AndroidProfile.u(str, cb, 1) and sync SubscriptionInfo if active.
            Class<?> pmFragClass = loader.loadClass("com.miui.euicc.ui.main.ProfileManageFragment");
            Class<?> nickDialogCbClass = loader.loadClass("com.miui.euicc.ui.main.ProfileManageFragment$f");
            Class<?> nickApduCbClass = loader.loadClass("com.miui.euicc.ui.main.ProfileManageFragment$f$a");
            Class<?> eCallbackInterface = loader.loadClass("com.miui.euicc.core.e$a");
            Class<?> androidProfileClass = loader.loadClass("com.miui.euicc.profileassistant.android.AndroidProfile");

            Field fProfile = pmFragClass.getDeclaredField("f");
            fProfile.setAccessible(true);
            Field fOuter = null;
            for (Field f : nickDialogCbClass.getDeclaredFields()) {
                if (f.getType() == pmFragClass) {
                    fOuter = f;
                    fOuter.setAccessible(true);
                    break;
                }
            }
            final Field outerField = fOuter;
            Constructor<?> apduCbCtor = nickApduCbClass.getDeclaredConstructor(nickDialogCbClass, String.class);
            apduCbCtor.setAccessible(true);
            Method getProfileIccid = androidProfileClass.getMethod("f");
            Method setProfileNickname = androidProfileClass.getMethod("u", String.class, eCallbackInterface, int.class);

            hook(nickDialogCbClass.getDeclaredMethod("b", String.class)).intercept(chain -> {
                if (!active() || outerField == null) return chain.proceed();
                String nick = (String) chain.getArg(0);
                if (nick != null && nick.getBytes(StandardCharsets.UTF_8).length > 64) {
                    return chain.proceed();
                }
                Object self = chain.getThisObject();
                Object frag = outerField.get(self);
                Object profile = frag != null ? fProfile.get(frag) : null;
                if (profile == null) return chain.proceed();
                try {
                    String iccid = (String) getProfileIccid.invoke(profile);
                    Object utilInst = getSimUtilInst.invoke(null);
                    syncNickSubInfoMethod.invoke(utilInst, nick, iccid);
                } catch (Throwable ignored) {
                }
                Object cb = apduCbCtor.newInstance(self, nick);
                setProfileNickname.invoke(profile, nick, cb, 1);
                return null;
            });
        } catch (Throwable t) {
            Log.w(TAG, "ProfileManageFragment nickname hook skipped: " + t.getClass().getSimpleName());
        }

        Log.i(TAG, "LPA hooks ready; dual-channel OMAPI+RIL SIM2 bridge and 61XX/6CXX APDU support active");
    }

    /**
     * Thread-safe OMAPI + RIL fallback session/channel manager for physical Slot 2 (SIM2) ISD-R APDU communication.
     */
    private static final class OmapiBridge {
        private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EsimH4-Omapi");
            t.setDaemon(true);
            return t;
        });
        private static final ExecutorService BIND_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EsimH4-OmapiBind");
            t.setDaemon(true);
            return t;
        });
        private static final Map<Integer, Channel> CHANNELS = new HashMap<>();
        private static final Map<Integer, Session> SESSIONS = new HashMap<>();
        private static final Set<Integer> RIL_CHANNELS = new HashSet<>();

        private static final Constructor<?> RESP_CTOR;
        private static final Method RESP_GET_STATUS;
        private static final Method RESP_GET_CHANNEL;

        static {
            Constructor<?> ctor = null;
            Method getStatus = null;
            Method getChannel = null;
            try {
                Class<?> respClass = Class.forName("android.telephony.IccOpenLogicalChannelResponse");
                ctor = respClass.getConstructor(int.class, int.class, byte[].class);
                getStatus = respClass.getMethod("getStatus");
                getChannel = respClass.getMethod("getChannel");
            } catch (Throwable ignored) {
            }
            RESP_CTOR = ctor;
            RESP_GET_STATUS = getStatus;
            RESP_GET_CHANNEL = getChannel;
        }

        private static SEService seService;
        private static Context fallbackContext;
        private static int nextChannelId = 12000;
        private static String cachedEid;
        private static long cachedEidAt;

        static synchronized void setContext(Context ctx) {
            if (ctx != null) {
                fallbackContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            }
        }

        private static synchronized Context resolveContext() {
            Context ctx = currentApp();
            return ctx != null ? ctx : fallbackContext;
        }

        static synchronized boolean owns(int id) {
            return CHANNELS.containsKey(id);
        }

        static synchronized void onRilOpen(int id) {
            RIL_CHANNELS.add(id);
        }

        static synchronized void onRilClose(int id) {
            RIL_CHANNELS.remove(id);
        }

        static synchronized boolean isBusy() {
            return !CHANNELS.isEmpty() || !RIL_CHANNELS.isEmpty();
        }

        static int getResponseStatus(Object response) throws Exception {
            return (int) RESP_GET_STATUS.invoke(response);
        }

        static int getResponseChannel(Object response) throws Exception {
            return (int) RESP_GET_CHANNEL.invoke(response);
        }

        private static synchronized Reader getSim2Reader() throws Exception {
            if (seService == null || !seService.isConnected()) {
                if (seService != null) {
                    try {
                        seService.shutdown();
                    } catch (Throwable ignored) {
                    }
                    seService = null;
                }
                Context ctx = resolveContext();
                if (ctx == null) throw new IllegalStateException("Application context unavailable");
                Context baseCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                // Android 15–17 SEService calls context.getApplicationContext(), then either:
                // 1) checks ServiceManager("android.se.omapi.ISecureElementService/default") synchronously and
                //    bounces listener.onConnected() via mContext.getMainExecutor(), or
                // 2) falls back to 3-arg mContext.bindService(...) which dispatches onto Main Looper by default.
                // Overriding getApplicationContext(), getMainExecutor(), and bindService(...) keeps every path off
                // Main Looper so LpaApplication.onCreate() / Main Thread can never self- or AB-BA-deadlock.
                Context bindCtx = new ContextWrapper(baseCtx) {
                    @Override
                    public Context getApplicationContext() {
                        return this;
                    }

                    @Override
                    public Executor getMainExecutor() {
                        return BIND_EXECUTOR;
                    }

                    @Override
                    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
                        return super.bindService(service, flags, BIND_EXECUTOR, conn);
                    }

                    @SuppressLint("NewApi")
                    @Override
                    public boolean bindService(Intent service, ServiceConnection conn, Context.BindServiceFlags flags) {
                        return super.bindService(service, flags, BIND_EXECUTOR, conn);
                    }
                };
                CountDownLatch latch = new CountDownLatch(1);
                SEService candidate = new SEService(bindCtx, EXECUTOR, latch::countDown);
                boolean connected = false;
                try {
                    connected = candidate.isConnected()
                            || (latch.await(8, TimeUnit.SECONDS) && candidate.isConnected());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    try {
                        candidate.shutdown();
                    } catch (Throwable ignored) {
                    }
                    if (t instanceof Exception) throw (Exception) t;
                    throw new RuntimeException(t);
                }
                if (!connected) {
                    try {
                        candidate.shutdown();
                    } catch (Throwable ignored) {
                    }
                    throw new IllegalStateException("OMAPI connection timed out");
                }
                seService = candidate;
            }
            Reader sim2 = null;
            Reader[] readers = seService.getReaders();
            if (readers != null) {
                for (Reader reader : readers) {
                    if (reader != null && reader.getName() != null && "SIM2".equalsIgnoreCase(reader.getName().replace(" ", ""))) {
                        sim2 = reader;
                        break;
                    }
                }
            }
            if (sim2 == null || !sim2.isSecureElementPresent()) {
                cachedEid = null;
                cachedEidAt = 0L;
                throw new IllegalStateException("SIM2 secure element unavailable");
            }
            return sim2;
        }

        static synchronized Object open(String aid, int p2) throws Exception {
            Reader sim2 = getSim2Reader();
            Session session = sim2.openSession();
            Channel channel = null;
            int id = -1;
            try {
                channel = session.openLogicalChannel(EuiccCardReader.fromHex(aid), (byte) p2);
                if (channel == null) {
                    throw new IllegalStateException("OMAPI logical channel unavailable");
                }
                id = nextChannelId++;
                CHANNELS.put(id, channel);
                SESSIONS.put(id, session);
                Log.i(TAG, "OMAPI SIM2 channel opened: id=" + id);
                if (RESP_CTOR == null) {
                    throw new IllegalStateException("IccOpenLogicalChannelResponse constructor unavailable");
                }
                return RESP_CTOR.newInstance(id, EuiccCardReader.RIL_STATUS_NO_ERROR, channel.getSelectResponse());
            } catch (Throwable e) {
                if (id >= 0) {
                    CHANNELS.remove(id);
                    SESSIONS.remove(id);
                }
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    session.close();
                } catch (Throwable ignored) {
                }
                if (e instanceof Exception) throw (Exception) e;
                throw new RuntimeException(e);
            }
        }

        static synchronized Object openSafe(String aid, int p2, Object rilFallback) {
            try {
                return open(aid, p2);
            } catch (Throwable e) {
                e = EuiccCardReader.unwrap(e);
                Log.w(TAG, "OMAPI open fallback unavailable: " + e.getClass().getSimpleName());
                if (e instanceof NoSuchElementException && RESP_CTOR != null) {
                    try {
                        return RESP_CTOR.newInstance(-1, EuiccCardReader.RIL_STATUS_NO_SUCH_ELEMENT, null);
                    } catch (Throwable ignored) {
                    }
                }
                if (rilFallback != null) {
                    return rilFallback;
                }
                try {
                    return RESP_CTOR.newInstance(-1, EuiccCardReader.RIL_STATUS_MISSING_RESOURCE, null);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }

        static synchronized void close(int id) {
            Channel channel = CHANNELS.remove(id);
            Session session = SESSIONS.remove(id);
            try {
                if (channel != null) channel.close();
            } finally {
                if (session != null) session.close();
            }
        }

        static synchronized String transmit(int id, int cla, int ins, int p1, int p2, int p3, String data) throws Exception {
            Channel ch = CHANNELS.get(id);
            if (ch == null) {
                throw new IllegalStateException("OMAPI channel not open: " + id);
            }
            byte[] body = (data == null || data.isEmpty()) ? new byte[0] : EuiccCardReader.fromHex(data);
            byte[] apdu;
            if (p3 < 0 && body.length == 0) {
                apdu = new byte[]{(byte) cla, (byte) ins, (byte) p1, (byte) p2};
            } else {
                apdu = new byte[5 + body.length];
                apdu[0] = (byte) cla;
                apdu[1] = (byte) ins;
                apdu[2] = (byte) p1;
                apdu[3] = (byte) p2;
                apdu[4] = (byte) p3;
                System.arraycopy(body, 0, apdu, 5, body.length);
            }
            // SecureElement Terminal.transmit normally resolves 6CXX/61XX and returns 9000;
            // transceiveApdu is a zero-cost pass-through on 9000 and safely finishes 6CXX/61XX
            // if any vendor SE HAL returns raw 6CXX/61XX, preventing LPA double-fetch or chunk loss.
            byte[] reply = EuiccCardReader.transceiveApdu(ch::transmit, apdu);
            return EuiccCardReader.toHexUpper(reply);
        }

        static String completeRilApduIfNeeded(
                TelephonyManager tm, int channel, int cla, int ins, int p1, int p2, int p3,
                String data, String initialHex) {
            if (tm == null || initialHex == null || initialHex.length() < 4) {
                return initialHex;
            }
            try {
                byte[] resp = EuiccCardReader.fromHex(initialHex.trim());
                if (resp.length < 2) return initialHex;
                int sw1 = resp[resp.length - 2] & 0xFF;
                if (sw1 != 0x6C && sw1 != 0x61) {
                    return initialHex;
                }
                IN_DIRECT_RIL.set(true);
                try {
                    if (resp.length == 2 && sw1 == 0x6C) {
                        int sw2 = resp[1] & 0xFF;
                        byte[] body = (data == null || data.isEmpty()) ? new byte[0] : EuiccCardReader.fromHex(data);
                        byte[] retryApdu;
                        if (body.length == 0) {
                            retryApdu = new byte[]{(byte) cla, (byte) ins, (byte) p1, (byte) p2, (byte) sw2};
                        } else if (body.length == p3 + 1) {
                            retryApdu = new byte[5 + body.length];
                            retryApdu[0] = (byte) cla;
                            retryApdu[1] = (byte) ins;
                            retryApdu[2] = (byte) p1;
                            retryApdu[3] = (byte) p2;
                            retryApdu[4] = (byte) p3;
                            System.arraycopy(body, 0, retryApdu, 5, body.length);
                            retryApdu[retryApdu.length - 1] = (byte) sw2;
                        } else {
                            retryApdu = new byte[5 + body.length + 1];
                            retryApdu[0] = (byte) cla;
                            retryApdu[1] = (byte) ins;
                            retryApdu[2] = (byte) p1;
                            retryApdu[3] = (byte) p2;
                            retryApdu[4] = (byte) p3;
                            System.arraycopy(body, 0, retryApdu, 5, body.length);
                            retryApdu[retryApdu.length - 1] = (byte) sw2;
                        }
                        resp = EuiccCardReader.transmitRilApdu(tm, 1, channel, retryApdu);
                    }
                    if (resp != null && resp.length >= 2 && (resp[resp.length - 2] & 0xFF) == 0x61) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        int chunks = 0;
                        while ((resp[resp.length - 2] & 0xFF) == 0x61) {
                            if (++chunks > EuiccCardReader.MAX_GET_RESPONSE_CHUNKS) {
                                throw new IllegalStateException("RIL GET RESPONSE chain exceeded "
                                        + EuiccCardReader.MAX_GET_RESPONSE_CHUNKS + " chunks");
                            }
                            if (resp.length > 2) {
                                out.write(resp, 0, resp.length - 2);
                            }
                            byte le = resp[resp.length - 1];
                            resp = EuiccCardReader.transmitRilApdu(
                                    tm, 1, channel, new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, le});
                            if (resp.length == 2 && (resp[0] & 0xFF) == 0x6C) {
                                resp = EuiccCardReader.transmitRilApdu(
                                        tm, 1, channel, new byte[]{0x00, (byte) 0xC0, 0x00, 0x00, resp[1]});
                            }
                        }
                        out.write(resp, 0, resp.length);
                        resp = out.toByteArray();
                    }
                    return EuiccCardReader.toHexUpper(resp);
                } finally {
                    IN_DIRECT_RIL.set(false);
                }
            } catch (IllegalStateException chainOverflow) {
                throw chainOverflow;
            } catch (Throwable t) {
                Log.w(TAG, "RIL 61XX/6CXX completion fallback skipped: " + t.getClass().getSimpleName());
                return initialHex;
            }
        }

        static synchronized String readEid(boolean forceRefresh) throws Exception {
            if (isBusy() && cachedEid != null) {
                return cachedEid;
            }
            if (!forceRefresh && cachedEid != null && SystemClock.elapsedRealtime() - cachedEidAt < 10000L) {
                Context ctx = resolveContext();
                if (ctx != null) {
                    TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
                    if (tm != null) {
                        if (tm.getSimState(1) != TelephonyManager.SIM_STATE_ABSENT) {
                            return cachedEid;
                        }
                        cachedEid = null;
                        cachedEidAt = 0L;
                    }
                }
            }

            Exception omapiError = null;
            Object response = null;
            try {
                response = open(EuiccCardReader.ISDR_AID, 0);
            } catch (Exception e) {
                if (isBusy() && cachedEid != null) {
                    return cachedEid;
                }
                Throwable root = EuiccCardReader.unwrap(e);
                if (root instanceof NoSuchElementException) {
                    cachedEid = null;
                    cachedEidAt = 0L;
                    throw (NoSuchElementException) root;
                }
                omapiError = e;
            }

            if (response != null) {
                int id = -1;
                try {
                    id = getResponseChannel(response);
                    if (id < 0) {
                        throw new IllegalStateException("Invalid channel ID from response: " + id);
                    }
                    Channel ch = CHANNELS.get(id);
                    if (ch == null) {
                        throw new IllegalStateException("OMAPI channel not registered: " + id);
                    }
                    String eid = null;
                    try {
                        byte[] bytes = EuiccCardReader.transceiveApdu(ch::transmit, EuiccCardReader.GET_EID_APDU_BF3E);
                        eid = EuiccCardReader.extractEid(bytes);
                    } catch (Throwable t) {
                        Log.d(TAG, "BF3E GET EID attempt failed: " + t.getMessage());
                    }
                    if (eid == null) {
                        try {
                            byte[] bytes = EuiccCardReader.transceiveApdu(ch::transmit, EuiccCardReader.GET_EID_APDU_BF2E);
                            eid = EuiccCardReader.extractEid(bytes);
                        } catch (Throwable t) {
                            Log.d(TAG, "BF2E GET EID attempt failed: " + t.getMessage());
                        }
                    }
                    if (eid != null) {
                        cachedEid = eid;
                        cachedEidAt = SystemClock.elapsedRealtime();
                        Log.i(TAG, "real EID read from SIM2 via OMAPI (value omitted)");
                        return cachedEid;
                    }
                    omapiError = new IllegalStateException("EID TLV missing on both BF3E and BF2E over OMAPI");
                } finally {
                    if (id >= 0) {
                        close(id);
                    }
                }
            }

            // Fallback to direct Telephony RIL logical channel (iccOpenLogicalChannelBySlot(1, ISDR_AID, 0))
            // when OMAPI fails with SecurityException (ARA-M) or SEService unavailability.
            Context ctx = resolveContext();
            if (ctx != null) {
                IN_DIRECT_RIL.set(true);
                try {
                    String rilEid = EuiccCardReader.readEidViaRil(ctx);
                    if (EuiccCardReader.isValidEid(rilEid)) {
                        cachedEid = rilEid;
                        cachedEidAt = SystemClock.elapsedRealtime();
                        Log.i(TAG, "real EID read from SIM2 via RIL fallback (value omitted)");
                        return cachedEid;
                    }
                } catch (NoSuchElementException normalSim) {
                    cachedEid = null;
                    cachedEidAt = 0L;
                    throw normalSim;
                } catch (Throwable rilErr) {
                    Log.w(TAG, "RIL EID fallback unavailable: " + rilErr.getClass().getSimpleName());
                } finally {
                    IN_DIRECT_RIL.set(false);
                }
            }

            if (omapiError != null) {
                throw omapiError;
            }
            throw new IllegalStateException("SIM2 EID unavailable on both OMAPI and RIL");
        }
    }
}
