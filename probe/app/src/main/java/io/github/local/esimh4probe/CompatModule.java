package io.github.local.esimh4probe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
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
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.euicc.DownloadableSubscription;
import android.util.Log;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * LSPosed compatibility module for Xiaomi MIX Flip 2 (bixi, OS4.0.0.11.XOHCNXM, Android 17 / SDK 37)
 * with a removable eUICC (ESTKme) in physical Slot 2 (SIM2).
 *
 * Isolates OEM soldered-eSIM GPIO/radio mux switching in com.android.phone, exposes verified SIM2
 * as default eUICC cardId 1, and adapts Xiaomi MIUIEsimLPA v4.1.0.1 to communicate with SIM2 via OMAPI.
 */
public final class CompatModule extends XposedModule {
    private static final String TAG = "EsimH4Compat";
    private static final int MIN_SDK = 35;
    private static final String MOCK_DOMAIN = "test.esim.local";

    private static final ThreadLocal<Boolean> IN_GET_CARDS = ThreadLocal.withInitial(() -> Boolean.FALSE);
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
            String text = original.toString();
            if ("打开后eSIM将替换SIM卡2".equals(text)) {
                return "与卡槽2中的eSIM卡同步开启或关闭";
            }
            if (text.contains("关闭eSIM并切换至SIM卡2")) {
                return "关闭eSIM将同时停用卡槽2中的eSIM卡。是否关闭？";
            }
            if (text.contains("开启eSIM") && text.contains("SIM卡2")
                    && (text.contains("关闭") || text.contains("不可用"))) {
                return "开启eSIM将同时启用卡槽2中的eSIM卡。是否开启？";
            }
            return original;
        });

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
                        boolean ready = tm != null && tm.getSimState(1) == TelephonyManager.SIM_STATE_READY;
                        return ready && Settings.Secure.getInt(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM, 0) == 1;
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
                            Sim2Subscription.resolve(ctx);
                            String prevSetting = Settings.Secure.getString(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM);
                            boolean enableSim2 = (state == 0);
                            if (!Settings.Secure.putInt(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM, enableSim2 ? 1 : 0)) {
                                return -1;
                            }
                            try {
                                Sim2Subscription.set(ctx, enableSim2);
                            } catch (Throwable t) {
                                Settings.Secure.putString(ctx.getContentResolver(), CardTypeGuard.KEY_ENABLE_ESIM, prevSetting);
                                Log.e(TAG, "SIM2 switch failed; logical state restored", t);
                                return -1;
                            }
                            Log.i(TAG, "eSIM switch=" + enableSim2 + "; SIM2 UICC applications=" + enableSim2);
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
            if (orig instanceof Integer && (int) orig >= 0) return orig;
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = resolvePhoneContext(phoneCtxField);
                if (ctx != null && CardTypeGuard.isEuicc(ctx)) return 1;
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
                for (Object item : (ArrayList<?>) orig) {
                    if (item instanceof UiccCardInfo) {
                        UiccCardInfo card = (UiccCardInfo) item;
                        if (card.getPhysicalSlotIndex() == 1) {
                            fIsEuicc.setBoolean(card, true);
                            if (card.getCardId() < 0) {
                                fCardId.setInt(card, 1);
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
            if (orig instanceof Integer && (int) orig >= 0) return orig;
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = resolvePhoneContext(phoneCtxField);
                if (ctx != null && CardTypeGuard.isEuicc(ctx)) return 1;
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
            if (orig instanceof Integer && (int) orig >= 0) return orig;
            long token = Binder.clearCallingIdentity();
            try {
                Context ctx = resolvePhoneContext(phoneCtxField);
                if (ctx != null && CardTypeGuard.isEuicc(ctx)) return 1;
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
                    result.putBoolean("lpa_port0_active", (boolean) isPortActiveMethod.invoke(utilInst, ctx, 0));
                }
            } catch (Throwable e) {
                while (e.getCause() != null) e = e.getCause();
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
            try {
                OmapiBridge.readEid(false);
                TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
                if (tm != null) {
                    for (UiccCardInfo card : tm.getUiccCardsInfo()) {
                        if (card != null && card.getPhysicalSlotIndex() == 1) {
                            return new ArrayList<>(card.getPorts());
                        }
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "verified SIM2 ports unavailable: " + e.getClass().getSimpleName());
            }
            return new ArrayList<>();
        });

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
            if (!active() || (int) chain.getArg(0) != 1) return chain.proceed();
            Object result = chain.proceed();
            if (result != null && OmapiBridge.getResponseStatus(result) == 1) {
                int chId = OmapiBridge.getResponseChannel(result);
                if (chId >= 0) {
                    OmapiBridge.onRilOpen(chId);
                }
                return result;
            }
            return OmapiBridge.openSafe((String) chain.getArg(1), (int) chain.getArg(2), result);
        });

        final boolean closeReturnsBoolean = (closeChMethod.getReturnType() == boolean.class);
        hook(closeChMethod).intercept(chain -> {
            if (!active()) return chain.proceed();
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
            if (!active() || (int) chain.getArg(0) != 1) return chain.proceed();
            int id = (int) chain.getArg(1);
            if (!OmapiBridge.owns(id)) return chain.proceed();
            return OmapiBridge.transmit(
                    id,
                    (int) chain.getArg(2),
                    (int) chain.getArg(3),
                    (int) chain.getArg(4),
                    (int) chain.getArg(5),
                    (int) chain.getArg(6),
                    (String) chain.getArg(7)
            );
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

        Log.i(TAG, "LPA hooks ready; OMAPI SIM2 fallback and one-click download bridge available");
    }

    /**
     * Thread-safe OMAPI session/channel manager for physical Slot 2 (SIM2) ISD-R APDU communication.
     */
    private static final class OmapiBridge {
        private static final String ISDR_AID = "A0000005591010FFFFFFFF8900000100";
        private static final byte[] GET_EID_APDU_BF3E = EuiccCardReader.fromHex("80E2910006BF3E035C015A00");
        private static final byte[] GET_EID_APDU_BF2E = EuiccCardReader.fromHex("80E2910006BF2E035C015A00");

        private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EsimH4-Omapi");
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

        private static Reader getSim2Reader() throws Exception {
            if (seService == null || !seService.isConnected()) {
                Context ctx = currentApp();
                if (ctx == null) ctx = fallbackContext;
                if (ctx == null) throw new IllegalStateException("Application context unavailable");
                CountDownLatch latch = new CountDownLatch(1);
                seService = new SEService(ctx, EXECUTOR, latch::countDown);
                if (!latch.await(8, TimeUnit.SECONDS) || !seService.isConnected()) {
                    seService = null;
                    throw new IllegalStateException("OMAPI connection timed out");
                }
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
                return RESP_CTOR.newInstance(id, 1, channel.getSelectResponse());
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
                while (e.getCause() != null) e = e.getCause();
                Log.w(TAG, "OMAPI open fallback unavailable: " + e.getClass().getSimpleName());
                try {
                    int status = (e instanceof NoSuchElementException) ? 3 : 2;
                    return RESP_CTOR.newInstance(-1, status, null);
                } catch (Throwable ignored) {
                    return rilFallback;
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
            byte[] apdu = new byte[5 + body.length];
            apdu[0] = (byte) cla;
            apdu[1] = (byte) ins;
            apdu[2] = (byte) p1;
            apdu[3] = (byte) p2;
            apdu[4] = (byte) p3;
            System.arraycopy(body, 0, apdu, 5, body.length);
            byte[] reply = ch.transmit(apdu);
            return EuiccCardReader.toHexUpper(reply);
        }

        private static String extractEid(byte[] bytes) {
            if (bytes == null || bytes.length < 20) return null;
            if (bytes[bytes.length - 2] != (byte) 0x90 || bytes[bytes.length - 1] != 0) return null;
            for (int i = 0; i + 17 < bytes.length - 2; i++) {
                if ((bytes[i] & 0xFF) == 0x5A && (bytes[i + 1] & 0xFF) == 16) {
                    byte[] eidBytes = new byte[16];
                    System.arraycopy(bytes, i + 2, eidBytes, 0, 16);
                    String candidate = EuiccCardReader.toHexUpper(eidBytes);
                    if (candidate.matches("[0-9]{32}")) {
                        return candidate;
                    }
                }
            }
            return null;
        }

        static synchronized String readEid(boolean forceRefresh) throws Exception {
            if (isBusy() && cachedEid != null) {
                return cachedEid;
            }
            if (!forceRefresh && cachedEid != null && SystemClock.elapsedRealtime() - cachedEidAt < 10000L) {
                try {
                    getSim2Reader();
                    return cachedEid;
                } catch (Throwable ignored) {
                }
            }
            Object response;
            try {
                response = open(ISDR_AID, 0);
            } catch (Exception e) {
                if (isBusy() && cachedEid != null) {
                    return cachedEid;
                }
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                if (root instanceof NoSuchElementException) {
                    cachedEid = null;
                    cachedEidAt = 0L;
                }
                throw e;
            }
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
                    byte[] bytes = ch.transmit(GET_EID_APDU_BF3E);
                    eid = extractEid(bytes);
                } catch (Throwable t) {
                    Log.d(TAG, "BF3E GET EID attempt failed: " + t.getMessage());
                }
                if (eid == null) {
                    try {
                        byte[] bytes = ch.transmit(GET_EID_APDU_BF2E);
                        eid = extractEid(bytes);
                    } catch (Throwable t) {
                        Log.d(TAG, "BF2E GET EID attempt failed: " + t.getMessage());
                    }
                }
                if (eid != null) {
                    cachedEid = eid;
                    cachedEidAt = SystemClock.elapsedRealtime();
                    Log.i(TAG, "real EID read from SIM2 (value omitted)");
                    return cachedEid;
                }
                throw new IllegalStateException("EID TLV missing on both BF3E and BF2E");
            } finally {
                if (id >= 0) {
                    close(id);
                } else {
                    close(nextChannelId - 1);
                }
            }
        }
    }
}
