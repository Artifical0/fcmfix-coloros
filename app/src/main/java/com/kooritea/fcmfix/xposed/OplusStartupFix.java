package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.content.pm.ApplicationInfo;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.FcmTrust;

import java.lang.reflect.Method;

import static com.kooritea.fcmfix.xposed.OplusHooks.*;

/**
 * ColorOS cold-start gates between GMS and a stopped target: classify lists, the GCM bind and
 * start-service paths, the broadcast startup restrict lists, malicious-app lists and
 * link-start limits. Each bypass requires a trusted GMS delivery to an allowlisted target.
 */
public class OplusStartupFix extends XposedModule {

    private static final String OPLUS_APP_STARTUP_MANAGER =
            "com.android.server.am.OplusAppStartupManager";
    private static final String OPLUS_STARTUP_STRATEGY =
            OPLUS_APP_STARTUP_MANAGER + "$OplusStartupStrategy";
    private static final String OPLUS_MALICIOUS_RESTRICT_POLICY =
            "com.android.server.am.MaliciousRestrictPolicy";
    private static final String OPLUS_LINK_START_MANAGER =
            "com.android.server.am.OplusLinkStartManager";
    private static final String TYPE_BIND_SERVICE_FROM_GCM = "bsgcm";
    private static final String START_PROCESS_FROM_GCM_BIND_SERVICE = "system[gcm]";

    public OplusStartupFix(ClassLoader classLoader) {
        super(classLoader);
        runHook("isAppClassifyRestricted", this::startHookAppClassifyRestricted);
        runHook("isAllowStartFromBindService", this::startHookGcmBindService);
        runHook("isAllowStartFromStartService", this::startHookFcmStartService);
        runHook("validStartProcessFromBroadcast", this::startHookValidStartFromBroadcast);
        runHook("malicious broadcast check", this::startHookMaliciousBroadcast);
        runHook("malicious service check", this::startHookMaliciousService);
        runHook("link-start broadcast check", this::startHookLinkStartBroadcast);
    }

    /**
     * ColorOS CN keeps per-component classify restriction lists that are skipped on the
     * international build. This check runs before the normal broadcast/service allow-list
     * decision, so only bypass it for an actual FCM intent addressed to an enabled target.
     */
    private void startHookAppClassifyRestricted() {
        Class<?> strategyClass = XposedHelpers.findClassIfExists(OPLUS_STARTUP_STRATEGY, classLoader);
        if (strategyClass == null) throw new NoClassDefFoundError(OPLUS_STARTUP_STRATEGY);

        int hooks = 0;
        for (Method method : strategyClass.getDeclaredMethods()) {
            if (!"isAppClassifyRestricted".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            // ColorOS 17 adds isAppClassifyRestricted(int,String,String,Long), a per-user
            // lookup with no Intent. It is not a delivery decision; skip it silently.
            if (!java.util.Arrays.asList(method.getParameterTypes()).contains(Intent.class)) continue;
            if (attributionIndex(method) < 0) {
                logOnce("Unsupported Oplus delivery signature: " + describeMethod(method));
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = findIntentArgument(param.args);
                    if (intent == null || !isFCMIntent(intent)) return;

                    String target = getIntentTarget(intent);
                    if (target == null) target = findCalleePackage(param.args);
                    if (trustedDelivery(intent, target, param)) {
                        printLog("Oplus classify restriction bypass: pkg=" + target
                                + ", action=" + intent.getAction(), true);
                        param.setResult(false);
                    }
                }
            });
            hooks++;
            printLog("Oplus classify hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isAppClassifyRestricted");
    }

    /**
     * On ColorOS 16 the GMS-to-app delivery path is tagged as type "bsgcm" and still
     * passes through isAllowAutoStartByList even when google_restric_info is disabled.
     */
    private void startHookGcmBindService() {
        Class<?> managerClass = XposedHelpers.findClassIfExists(OPLUS_APP_STARTUP_MANAGER, classLoader);
        if (managerClass == null) throw new NoClassDefFoundError(OPLUS_APP_STARTUP_MANAGER);

        int hooks = 0;
        for (Method method : managerClass.getDeclaredMethods()) {
            if (!"isAllowStartFromBindService".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            if (attributionIndex(method) < 0) {
                logOnce("Unsupported Oplus delivery signature: " + describeMethod(method));
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!hasStringArgument(param.args, TYPE_BIND_SERVICE_FROM_GCM)
                            && !hasStringArgument(param.args, START_PROCESS_FROM_GCM_BIND_SERVICE)) {
                        return;
                    }
                    String target = findCalleePackage(param.args);
                    if (trustedDelivery(findIntentArgument(param.args), target, param)) {
                        printLog("Oplus GCM bind-service bypass: pkg=" + target, true);
                        param.setResult(true);
                    }
                }
            });
            hooks++;
            printLog("Oplus GCM bind hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isAllowStartFromBindService");
    }

    /** Older Firebase delivery variants use startService instead of the GCM bind path. */
    private void startHookFcmStartService() {
        Class<?> managerClass = XposedHelpers.findClassIfExists(OPLUS_APP_STARTUP_MANAGER, classLoader);
        if (managerClass == null) throw new NoClassDefFoundError(OPLUS_APP_STARTUP_MANAGER);

        int hooks = 0;
        for (Method method : managerClass.getDeclaredMethods()) {
            if (!"isAllowStartFromStartService".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            if (attributionIndex(method) < 0) {
                logOnce("Unsupported Oplus delivery signature: " + describeMethod(method));
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = findIntentArgument(param.args);
                    if (intent == null || !isFCMIntent(intent)) return;

                    String target = findCalleePackage(param.args);
                    if (trustedDelivery(intent, target, param)) {
                        printLog("Oplus FCM start-service bypass: pkg=" + target
                                + ", action=" + intent.getAction(), true);
                        param.setResult(true);
                    }
                }
            });
            hooks++;
            printLog("Oplus FCM start hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isAllowStartFromStartService");
    }

    private interface TargetResolver {
        String resolve(Object[] args);
    }

    /**
     * Cold-start gates on the queued broadcast path. Each receives the BroadcastRecord, so
     * trustedDelivery verifies the real GMS sender, the exact RECEIVE action and the
     * allowlisted explicit target before the gate is told not to block.
     */
    private int hookTrustedBroadcastGate(String className, String methodName, Object allowResult,
                                         TargetResolver resolver) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) throw new NoClassDefFoundError(className);
        int hooks = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || !isBooleanType(method.getReturnType())) continue;
            boolean hasRecord = false;
            for (Class<?> type : method.getParameterTypes()) {
                if ("com.android.server.am.BroadcastRecord".equals(type.getName())) hasRecord = true;
            }
            if (!hasRecord) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = findIntentArgument(param.args);
                    if (intent == null || !FcmTrust.RECEIVE.equals(intent.getAction())) return;
                    String target;
                    try {
                        target = resolver.resolve(param.args);
                    } catch (Throwable e) {
                        return;
                    }
                    if (trustedDelivery(intent, target, param)) {
                        printLog("Oplus " + methodName + " bypass: pkg=" + target, true);
                        param.setResult(allowResult);
                    }
                }
            });
            hooks++;
            printLog("Oplus broadcast gate hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError(className + "#" + methodName);
        return hooks;
    }

    private static String receiverPackage(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof android.content.pm.ResolveInfo) {
                android.content.pm.ResolveInfo info = (android.content.pm.ResolveInfo) arg;
                return info.activityInfo == null ? null : info.activityInfo.packageName;
            }
            if (arg != null && "com.android.server.am.BroadcastFilter".equals(arg.getClass().getName())) {
                Object pkg = XposedHelpers.getObjectField(arg, "packageName");
                return pkg instanceof String ? (String) pkg : null;
            }
        }
        return null;
    }

    /**
     * ColorOS skips a cold start from broadcast when the target is in the persistent restrict
     * list, or in the background-startup restrict list while it has no process. That is the
     * exact case FCMFix exists for: GMS waking a stopped, user-allowlisted app.
     */
    private void startHookValidStartFromBroadcast() {
        hookTrustedBroadcastGate(OPLUS_APP_STARTUP_MANAGER, "validStartProcessFromBroadcast", false, args -> {
            for (Object arg : args) if (arg instanceof String) return (String) arg;
            return null;
        });
    }

    /** Cloud "malicious app" lists can block a whole package's receivers, FCM included. */
    private void startHookMaliciousBroadcast() {
        hookTrustedBroadcastGate(OPLUS_MALICIOUS_RESTRICT_POLICY, "shouldPreventBroadcastByMaliciousCheck",
                false, OplusStartupFix::receiverPackage);
    }

    /** Link-start limits count GMS as the launcher when it wakes many apps with FCM. */
    private void startHookLinkStartBroadcast() {
        hookTrustedBroadcastGate(OPLUS_LINK_START_MANAGER, "handleProcessBroadcastStartLocked",
                false, OplusStartupFix::receiverPackage);
    }

    /**
     * The same malicious list gates service start/bind/restart. Only bindings from a real
     * ProcessRecord are considered: GMS binding the target for FCM/GCM, or the target binding
     * its own FirebaseMessagingService inside its FCM delivery window (current Firebase SDKs).
     */
    private void startHookMaliciousService() {
        Class<?> policyClass = XposedHelpers.findClassIfExists(OPLUS_MALICIOUS_RESTRICT_POLICY, classLoader);
        if (policyClass == null) throw new NoClassDefFoundError(OPLUS_MALICIOUS_RESTRICT_POLICY);

        int hooks = 0;
        for (Method method : policyClass.getDeclaredMethods()) {
            if (!"shouldPreventServiceByMaliciousCheck".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isBootComplete) return;
                    Object callerApp = null;
                    Object serviceRecord = null;
                    Intent intent = null;
                    String type = null;
                    for (Object arg : param.args) {
                        if (arg == null) continue;
                        String name = arg.getClass().getName();
                        if ("com.android.server.am.ProcessRecord".equals(name)) callerApp = arg;
                        else if ("com.android.server.am.ServiceRecord".equals(name)) serviceRecord = arg;
                        else if (arg instanceof Intent) intent = (Intent) arg;
                        else if (arg instanceof String) type = (String) arg;
                    }
                    if (callerApp == null || serviceRecord == null || intent == null) return;
                    try {
                        Object info = XposedHelpers.getObjectField(serviceRecord, "appInfo");
                        if (!(info instanceof ApplicationInfo)) return;
                        ApplicationInfo appInfo = (ApplicationInfo) info;
                        String target = appInfo.packageName;
                        if (!targetIsAllow(target)) return;
                        int callerUid = (Integer) XposedHelpers.getObjectField(callerApp, "uid");
                        boolean gms = isGmsUid(callerUid);
                        boolean selfInWindow = callerUid == appInfo.uid && OplusProxyFix.isInFcmDeliveryWindow(callerUid);
                        boolean gcmBind = "bind".equals(type) && gms;
                        if (FcmTrust.allowsService(intent.getAction(), gms, selfInWindow, gcmBind)) {
                            if (gms) OplusProxyFix.beginFcmDeliveryWindow(target);
                            printLog("Oplus malicious service bypass: pkg=" + target
                                    + ", action=" + intent.getAction(), true);
                            param.setResult(false);
                        }
                    } catch (Throwable e) {
                        logOnce("Unsupported malicious service attribution: " + e);
                    }
                }
            });
            hooks++;
            printLog("Oplus malicious service hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("shouldPreventServiceByMaliciousCheck");
    }

    private String findCalleePackage(Object[] args) {
        Intent intent = findIntentArgument(args);
        if (intent != null) {
            String target = getIntentTarget(intent);
            if (target != null) return target;
        }
        for (Object arg : args) {
            if (arg instanceof ApplicationInfo) {
                return ((ApplicationInfo) arg).packageName;
            }
            if (arg == null) continue;
            try {
                Object appInfo = XposedHelpers.getObjectField(arg, "appInfo");
                if (appInfo instanceof ApplicationInfo) {
                    return ((ApplicationInfo) appInfo).packageName;
                }
            } catch (Throwable ignored) {
            }
        }
        return findAllowedPackageArgument(args);
    }

    private boolean hasStringArgument(Object[] args, String expected) {
        for (Object arg : args) {
            if (expected.equals(arg)) return true;
        }
        return false;
    }
}
