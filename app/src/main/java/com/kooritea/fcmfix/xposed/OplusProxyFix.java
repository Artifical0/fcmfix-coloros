package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.WorkSource;
import android.util.Pair;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.FcmTrust;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

public class OplusProxyFix extends XposedModule {

    private static final String OPLUS_APP_STARTUP_MANAGER =
            "com.android.server.am.OplusAppStartupManager";
    private static final String OPLUS_STARTUP_STRATEGY =
            OPLUS_APP_STARTUP_MANAGER + "$OplusStartupStrategy";
    private static final String OPLUS_HANS_DB_CONFIG =
            "com.android.server.hans.OplusHansDBConfig";
    private static final String OPLUS_APP_NET_CONTROL_SERVICE =
            "com.android.server.nwpower.OAppNetControlService";
    private static final String OPLUS_HANS_SCENE_MANAGER =
            "com.android.server.hans.scene.HansSceneManager";
    private static final String OPLUS_HANS_CGROUP =
            "com.android.server.hans.freeze.HansCGroup";
    private static final String OPLUS_BROADCAST_PROXY_ACTION =
            "com.android.server.am.BroadcastProxyAction";
    private static final String OPLUS_BG_SCENE_MANAGER =
            "com.android.server.hans.scene.OplusBgSceneManager";
    private static final String OPLUS_HANS_CONNECTIVITY_MANAGER =
            "com.android.server.hans.device.OplusHansConnectivityManager";
    private static final String OPLUS_MALICIOUS_RESTRICT_POLICY =
            "com.android.server.am.MaliciousRestrictPolicy";
    private static final String GOOGLE_GMS_PACKAGE = "com.google.android.gms";
    private static final String OPLUS_NETWORK_MANAGEMENT_SERVICE =
            "com.android.server.net.OplusNetworkManagementService";
    private static final String[] GOOGLE_NETWORK_PACKAGES = new String[]{
            GOOGLE_GMS_PACKAGE,
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.configupdater"
    };
    private static final int FIREWALL_RULE_REJECT = 2;
    private static final String GOOGLE_RESTRICT_CHANGE = "oplus.intent.action.google_restrict_change";
    private static final String OSENSE_RES_MANAGER_SERVICE =
            "com.android.server.oplus.osense.OsenseResManagerService";
    private static final String DEEP_SLEEP_GMS_CLEAN_REASON = "DeepSleepLogicDisNetRestore";
    private static final String OPLUS_HANS_MANAGER =
            "com.android.server.am.OplusHansManager";
    private static final String OPLUS_LINK_START_MANAGER =
            "com.android.server.am.OplusLinkStartManager";
    private static final String TYPE_BIND_SERVICE_FROM_GCM = "bsgcm";
    private static final String START_PROCESS_FROM_GCM_BIND_SERVICE = "system[gcm]";
    private static final long FCM_DELIVERY_WINDOW_MS = 20_000L;

    /**
     * ColorOS can unfreeze an FCM target, deliver the broadcast and freeze it again about
     * three seconds later. OAppNetControlService then destroys the target UID's sockets,
     * so applications such as WeChat may not finish fetching the actual message. Keep only
     * the UID involved in the current FCM delivery unfrozen and online for the same kind of
     * short execution window Android grants to high-priority push work.
     */
    private static final com.kooritea.fcmfix.util.FcmDeliveryWindow sFcmDeliveryWindows =
            new com.kooritea.fcmfix.util.FcmDeliveryWindow();
    private static final ConcurrentHashMap<Integer, String> sFcmDeliveryPackages =
            new ConcurrentHashMap<>();

    private static final String[] PROXY_BROADCAST_CLASSES = new String[]{
            "com.android.server.am.OplusProxyBroadcast",
            "com.android.server.am.OplusBroadcastProxy",
            "com.oplus.server.am.OplusProxyBroadcast"
    };

    private static final String[] PROXY_WAKELOCK_CLASSES = new String[]{
            "com.android.server.power.OplusProxyWakeLock",
            "com.android.server.power.oplus.OplusProxyWakeLock",
            "com.oplus.server.power.OplusProxyWakeLock"
    };

    private static volatile Object sOplusProxyWakeLock;
    private static volatile Method sUnfreezeMethod;

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        runHook("OplusProxyWakeLock", this::startHookOplusProxyWakeLock);
        runHook("OplusProxyBroadcast", this::startHookOplusProxyBroadcast);
        runHook("registerGmsRestrictObserver", this::startHookRegisterGmsRestrictObserver);
        runHook("updateGmsRestrict", this::startHookUpdateGmsRestrict);
        runHook("isGoogleRestricInfoOn", this::startHookIsGoogleRestricInfoOn);
        runHook("isAppClassifyRestricted", this::startHookAppClassifyRestricted);
        runHook("isAllowStartFromBindService", this::startHookGcmBindService);
        runHook("isAllowStartFromStartService", this::startHookFcmStartService);
        runHook("isSysRestrictionCpn", this::startHookHansGmsRestriction);
        runHook("OAppNetControlService", this::startHookOAppNetControlService);
        runHook("HansSceneManager FCM window", this::startHookHansFcmWindow);
        runHook("HansCGroup FCM window", this::startHookHansCGroupFcmWindow);
        runHook("CpnProxy broadcast", this::startHookCpnProxyBroadcast);
        runHook("isGmsRestricted", this::startHookIsGmsRestricted);
        runHook("weak-signal net whitelist", this::startHookWeakSignalNetWhiteList);
        runHook("validStartProcessFromBroadcast", this::startHookValidStartFromBroadcast);
        runHook("malicious broadcast check", this::startHookMaliciousBroadcast);
        runHook("malicious service check", this::startHookMaliciousService);
        runHook("link-start broadcast check", this::startHookLinkStartBroadcast);
        runHook("Hans job FCM window", this::startHookHansJobWindow);
        runHook("night network whitelist", this::startHookNightNetworkWhitelist);
        runHook("Google network firewall", this::startHookGoogleNetworkFirewall);
        runHook("Google restrict broadcast", this::startHookGoogleRestrictBroadcast);
        runHook("deep-sleep GMS force-stop", this::startHookDeepSleepGmsForceStop);
    }

    /**
     * When the screen turns on after a battery deep-sleep "logical" network cut, Battery asks
     * Osense to clean GMS ("DeepSleepLogicDisNetRestore", strategy 2 = force-stop) so that it
     * reconnects. A force-stop also cancels every GMS alarm and leaves it stopped until a
     * client binds it. GMS now stays on the deep-sleep network whitelist and gets
     * GCM_RECONNECT after the restore, so skip only this force-stop. The Osense Binder
     * implementation is an anonymous class of OsenseResManagerService.
     */
    private void startHookDeepSleepGmsForceStop() {
        int hooks = 0;
        for (int i = 1; i <= 30 && hooks == 0; i++) {
            Class<?> clazz = XposedHelpers.findClassIfExists(OSENSE_RES_MANAGER_SERVICE + "$" + i, classLoader);
            if (clazz == null) continue;
            for (Method method : clazz.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"requestSceneActionSync".equals(method.getName()) || types.length != 1
                        || types[0] != android.os.Bundle.class || !isBooleanType(method.getReturnType())) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.args[0] instanceof android.os.Bundle)) return;
                        android.os.Bundle bundle = (android.os.Bundle) param.args[0];
                        if (!GOOGLE_GMS_PACKAGE.equals(bundle.getString("pkgName"))
                                || !DEEP_SLEEP_GMS_CLEAN_REASON.equals(bundle.getString("reason"))) {
                            return;
                        }
                        param.setResult(Boolean.FALSE);
                        printLog("Oplus deep-sleep GMS force-stop skipped", true);
                    }
                });
                hooks++;
                printLog("Oplus deep-sleep GMS force-stop hook active: " + describeMethod(method));
            }
        }
        if (hooks == 0) throw new NoSuchMethodError("OsenseResManagerService$*#requestSceneActionSync(Bundle)");
    }

    /**
     * Battery broadcasts google_restrict_change(restrict_enable=true) when its Google probe
     * fails. Three system_server receivers act on it: OplusGoogleRestrictionHelper (GMS wakeup
     * alarms downgraded), AppStandbyControllerExtImpl (RARE bucket) and
     * OplusNetworkPolicyManagerServiceEx. The battery-scope hook clears the flag at the sender;
     * this clears it at the Binder entry so a missing battery scope, or an inlined getter such
     * as isGoogleRestrct(), cannot leave GMS restricted. List updates still go through.
     */
    private void startHookGoogleRestrictBroadcast() {
        String[] classes = new String[]{
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        int hooks = 0;
        for (String className : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) continue;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"broadcastIntentWithFeature".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                int intentIndex = -1;
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == Intent.class) {
                        intentIndex = i;
                        break;
                    }
                }
                if (intentIndex < 0) continue;
                final int index = intentIndex;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object arg = param.args[index];
                        if (!(arg instanceof Intent)) return;
                        Intent intent = (Intent) arg;
                        if (!GOOGLE_RESTRICT_CHANGE.equals(intent.getAction())
                                || !intent.getBooleanExtra("restrict_enable", false)) {
                            return;
                        }
                        intent.putExtra("restrict_enable", false);
                        printLog("Oplus Google restrict broadcast cleared in system_server", true);
                    }
                });
                hooks++;
                printLog("Oplus Google restrict broadcast hook active: " + describeMethod(method));
            }
            // AMS delegates to BroadcastController; one entry hook is enough.
            if (hooks > 0) break;
        }
        if (hooks == 0) throw new NoSuchMethodError("broadcastIntentWithFeature(Intent)");
    }

    /**
     * Battery's GoogleRestrictionController probes Google 5 s after BOOT_COMPLETED (i.e. right
     * after the first unlock) and, if the probe fails, sets the Google core UIDs to
     * POLICY_REJECT_ALL. OplusExSystemService's networking_control service turns that into
     * setFirewallUidRuleForNetworkType(type, uid, 2) here; GMS then sits disconnected with
     * ERR_CLOSE_BY_USER_UNLOCKED. The battery-scope hook rewrites the policy at its source,
     * but it depends on the battery scope and on the call not being inlined, so drop the
     * reject rule at this system_server boundary as well. Clears (rule 1) still pass.
     * This boundary cannot tell the battery from Traffic Monitor, so it only acts while
     * the Google toggles are hidden (IgnoreGmsUserSet); without that declaration, manual
     * Wi-Fi/mobile rules are left alone.
     */
    private void startHookGoogleNetworkFirewall() {
        Class<?> serviceClass = XposedHelpers.findClassIfExists(OPLUS_NETWORK_MANAGEMENT_SERVICE, classLoader);
        if (serviceClass == null) throw new NoClassDefFoundError(OPLUS_NETWORK_MANAGEMENT_SERVICE);

        int hooks = 0;
        for (Method method : serviceClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"setFirewallUidRuleForNetworkType".equals(method.getName()) || types.length != 3
                    || types[0] != int.class || types[1] != int.class || types[2] != int.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int uid = (Integer) param.args[1];
                    if ((Integer) param.args[2] != FIREWALL_RULE_REJECT || !isGoogleNetworkUid(uid)
                            || !OplusBatteryNetworkFix.batteryIgnoresGmsUserSet()) return;
                    param.setResult(null);
                    printLog("Oplus Google network reject dropped: uid=" + uid
                            + ", type=" + param.args[0], true);
                }
            });
            hooks++;
            printLog("Oplus Google network firewall hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("OplusNetworkManagementService#setFirewallUidRuleForNetworkType");
    }

    private static boolean isGoogleNetworkUid(int uid) {
        if (context == null) return false;
        int appId = uid % 100_000;
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : GOOGLE_NETWORK_PACKAGES) {
            try {
                if (packageManager.getPackageUid(packageName, 0) % 100_000 == appId) return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * Battery deep sleep cuts the network with OAppNetControlService.networkDisableWhiteList
     * (enable != 1 starts, enable == 1 restores). Field logs showed the battery-side list
     * reaching the service without the GMS UID, so GMS lost its socket at night
     * (ERR_IO_RST_HB) and never retried after the restore. Add the GMS UID at the service
     * boundary, then ask GMS to reconnect once the network is back.
     */
    private void startHookNightNetworkWhitelist() {
        Class<?> serviceClass = XposedHelpers.findClassIfExists(OPLUS_APP_NET_CONTROL_SERVICE, classLoader);
        if (serviceClass == null) throw new NoClassDefFoundError(OPLUS_APP_NET_CONTROL_SERVICE);

        int hooks = 0;
        for (Method method : serviceClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"networkDisableWhiteList".equals(method.getName()) || types.length != 2
                    || !java.util.List.class.isAssignableFrom(types[0]) || types[1] != int.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if ((Integer) param.args[1] == 1 || !(param.args[0] instanceof java.util.List)) return;
                    int uid = getTargetUidFromPackageName(GOOGLE_GMS_PACKAGE);
                    if (uid < 0) return;
                    java.util.List<?> original = (java.util.List<?>) param.args[0];
                    String entry = String.valueOf(uid);
                    for (Object item : original) {
                        if (item != null && (entry.equals(item) || item.toString().startsWith(entry + ":"))) return;
                    }
                    java.util.List<Object> whitelist = new java.util.ArrayList<>(original);
                    whitelist.add(entry);
                    param.args[0] = whitelist;
                    printLog("Oplus night network whitelist: added GMS uid=" + uid, true);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if ((Integer) param.args[1] != 1 || !(param.getResult() instanceof Integer)
                            || (Integer) param.getResult() != 0 || context == null) {
                        return;
                    }
                    // Give netd a moment to drop the whitelist chain before GMS reconnects.
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            OplusProxyFix::requestGmsReconnect, 3000);
                }
            });
            hooks++;
            printLog("Oplus night network whitelist hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("OAppNetControlService#networkDisableWhiteList");
    }

    /**
     * Same broadcast as FCM Diagnostics' RECONNECT; GMS reconnects if its MCS link is down.
     * Runs in system_server: lint reads the module manifest, so the host permission is
     * checked at runtime here instead of being requested by the module APK.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private static void requestGmsReconnect() {
        try {
            if (context.checkSelfPermission("android.permission.INTERACT_ACROSS_USERS")
                    != PackageManager.PERMISSION_GRANTED) {
                printLog("GCM_RECONNECT skipped: host cannot send a user-qualified broadcast");
                return;
            }
            Intent reconnect = new Intent("com.google.android.intent.action.GCM_RECONNECT");
            reconnect.setPackage(GOOGLE_GMS_PACKAGE);
            context.sendBroadcastAsUser(reconnect, android.os.Process.myUserHandle());
            printLog("Oplus night network restored: GCM_RECONNECT sent", true);
        } catch (Throwable e) {
            printLog("GCM_RECONNECT after night network restore failed: " + e);
        }
    }

    /**
     * Apps such as Gmail react to an FCM tickle by scheduling a sync/WorkManager job, which Hans
     * blocks for background apps. Only the UID inside its FCM delivery window is exempted.
     * Generalized from a Gmail-only fork change by @Tlipoca1337.
     */
    private void startHookHansJobWindow() {
        Class<?> hansClass = XposedHelpers.findClassIfExists(OPLUS_HANS_MANAGER, classLoader);
        if (hansClass == null) throw new NoClassDefFoundError(OPLUS_HANS_MANAGER);

        int hooks = 0;
        for (Method method : hansClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"checkJobIfRestricted".equals(method.getName()) || !isBooleanType(method.getReturnType())
                    || types.length < 2 || types[0] != int.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int uid = (Integer) param.args[0];
                    if (isInFcmDeliveryWindow(uid)) {
                        printLog("Oplus FCM job-restriction bypass: pkg="
                                + getFcmDeliveryPackage(uid) + ", uid=" + uid, true);
                        param.setResult(false);
                    }
                }
            });
            hooks++;
            printLog("Oplus Hans job FCM-window hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("OplusHansManager#checkJobIfRestricted");
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
                false, OplusProxyFix::receiverPackage);
    }

    /** Link-start limits count GMS as the launcher when it wakes many apps with FCM. */
    private void startHookLinkStartBroadcast() {
        hookTrustedBroadcastGate(OPLUS_LINK_START_MANAGER, "handleProcessBroadcastStartLocked",
                false, OplusProxyFix::receiverPackage);
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
                        boolean selfInWindow = callerUid == appInfo.uid && isInFcmDeliveryWindow(callerUid);
                        boolean gcmBind = "bind".equals(type) && gms;
                        if (FcmTrust.allowsService(intent.getAction(), gms, selfInWindow, gcmBind)) {
                            if (gms) beginFcmDeliveryWindow(target);
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

    /**
     * updateGmsRestrict is already a no-op, but several consumers read the state directly:
     * OplusProxyWakeLock drops GMS/GSF partial wakelocks while it is true, and Hans/OGuard
     * treat GMS as restricted. Pin the getter so no other writer can re-enable those paths.
     */
    private void startHookIsGmsRestricted() {
        int hooks = hookAllMethods(OPLUS_BG_SCENE_MANAGER, "isGmsRestricted", Boolean.FALSE);
        if (hooks == 0) throw new NoSuchMethodError("isGmsRestricted");
    }

    /**
     * With the screen off in a weak-signal scene, Hans firewalls Doze-whitelisted apps that
     * include its GMS list (chain 9) and blocks their alarms, which drops the FCM connection.
     * Report only the Google core packages as whitelisted for that scene.
     */
    private void startHookWeakSignalNetWhiteList() {
        Class<?> managerClass = XposedHelpers.findClassIfExists(OPLUS_HANS_CONNECTIVITY_MANAGER, classLoader);
        if (managerClass == null) throw new NoClassDefFoundError(OPLUS_HANS_CONNECTIVITY_MANAGER);

        int hooks = 0;
        for (Method method : managerClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"isWeakSignalNetWhiteList".equals(method.getName()) || !isBooleanType(method.getReturnType())
                    || types.length != 1 || types[0] != String.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] instanceof String && isGoogleCorePackage((String) param.args[0])) {
                        param.setResult(true);
                    }
                }
            });
            hooks++;
            printLog("Oplus weak-signal whitelist hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isWeakSignalNetWhiteList(String)");
    }

    private interface HookAction {
        void run() throws Throwable;
    }

    private void runHook(String name, HookAction action) {
        try {
            action.run();
        } catch (Throwable e) {
            printLog("hook error " + name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void startHookOplusProxyBroadcast() {
        int hookCount = 0;
        for (String className : PROXY_BROADCAST_CLASSES) {
            Class<?> proxyClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (proxyClass == null) {
                continue;
            }

            for (Method method : proxyClass.getDeclaredMethods()) {
                if (!"shouldProxy".equals(method.getName())) {
                    continue;
                }
                Object noProxyResult = getNoProxyResult(method.getReturnType());
                if (noProxyResult == UnsupportedResult.VALUE) {
                    printLog("unsupported shouldProxy candidate: " + describeMethod(method));
                    continue;
                }

                final Object finalNoProxyResult = noProxyResult;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Intent intent = findIntentArgument(param.args);
                        if (intent == null || !isFCMAction(intent.getAction())) {
                            return;
                        }

                        String target = getIntentTarget(intent);
                        if (target == null) {
                            target = findAllowedPackageArgument(param.args);
                        }
                        if (trustedDelivery(intent, target, param)) {
                            printLog("Oplus shouldProxy bypass: pkg=" + target
                                    + ", action=" + intent.getAction(), true);
                            param.setResult(finalNoProxyResult);
                        }
                    }
                });
                hookCount++;
                printLog("Oplus shouldProxy hook active: " + describeMethod(method));
            }
        }
        if (hookCount == 0) {
            throw new NoSuchMethodError("No compatible Oplus shouldProxy method");
        }
    }

    /**
     * ColorOS 17 Osense scene proxy (game / app start / camera) can hold broadcasts for cold
     * targets. Its action list is loaded from cloud-updatable config, so exempt only a
     * BroadcastRecord-attributed GMS RECEIVE to an allowlisted target.
     */
    private void startHookCpnProxyBroadcast() {
        Class<?> actionClass = XposedHelpers.findClassIfExists(OPLUS_BROADCAST_PROXY_ACTION, classLoader);
        if (actionClass == null) throw new NoClassDefFoundError(OPLUS_BROADCAST_PROXY_ACTION);

        int hooks = 0;
        for (Method method : actionClass.getDeclaredMethods()) {
            if (!"enqueueProxyBroadcastLocked".equals(method.getName())
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = findIntentArgument(param.args);
                    if (intent == null || !isFCMAction(intent.getAction())) return;
                    String target = getIntentTarget(intent);
                    if (trustedDelivery(intent, target, param)) {
                        printLog("Oplus CpnProxy broadcast bypass: pkg=" + target, true);
                        param.setResult(false);
                    }
                }
            });
            hooks++;
            printLog("Oplus CpnProxy broadcast hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("BroadcastProxyAction#enqueueProxyBroadcastLocked");
    }

    private void startHookOplusProxyWakeLock() {
        Class<?> wakeLockClass = null;
        for (String className : PROXY_WAKELOCK_CLASSES) {
            wakeLockClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (wakeLockClass != null) {
                break;
            }
        }
        if (wakeLockClass == null) {
            throw new NoClassDefFoundError("OplusProxyWakeLock");
        }

        sUnfreezeMethod = findBestUnfreezeMethod(wakeLockClass);
        if (sUnfreezeMethod == null) {
            throw new NoSuchMethodError(wakeLockClass.getName() + "#unfreezeIfNeed");
        }
        sUnfreezeMethod.setAccessible(true);
        printLog("Oplus unfreeze method selected: " + describeMethod(sUnfreezeMethod));

        if (Modifier.isStatic(sUnfreezeMethod.getModifiers())) {
            return;
        }

        int constructorHooks = 0;
        for (Constructor<?> constructor : wakeLockClass.getDeclaredConstructors()) {
            constructor.setAccessible(true);
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sOplusProxyWakeLock = param.thisObject;
                    printLog("OplusProxyWakeLock instance captured");
                }
            });
            constructorHooks++;
        }
        if (constructorHooks == 0) {
            throw new NoSuchMethodError(wakeLockClass.getName() + "#<init>");
        }
    }

    private Method findBestUnfreezeMethod(Class<?> clazz) {
        Method best = null;
        int bestScore = -1;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"unfreezeIfNeed".equals(method.getName())) {
                continue;
            }
            int score = 0;
            for (Class<?> type : method.getParameterTypes()) {
                if (type == int.class || type == Integer.class) score += 4;
                if (WorkSource.class.isAssignableFrom(type)) score += 3;
                if (type == String.class) score += 1;
            }
            if (score > bestScore) {
                best = method;
                bestScore = score;
            }
        }
        return best;
    }

    private static int getTargetUidFromPackageName(String packageName) {
        if (packageName != null && context != null) {
            try {
                return context.getPackageManager().getPackageUid(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                printLog("error: Package not found: " + packageName);
            }
        }
        return -1;
    }

    public static void unfreeze(String target) {
        Method method = sUnfreezeMethod;
        if (method == null) {
            return;
        }
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : sOplusProxyWakeLock;
        if (!Modifier.isStatic(method.getModifiers()) && receiver == null) {
            return;
        }

        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) {
            return;
        }

        Object[] args = createUnfreezeArguments(method.getParameterTypes(), uid);
        if (args == null) {
            printLog("unsupported Oplus unfreeze arguments: " + describeMethod(method));
            return;
        }

        try {
            method.invoke(receiver, args);
            printLog("unfreeze " + target + ", uid=" + uid, true);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            printLog("Oplus unfreeze invocation failed: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
        } catch (Throwable e) {
            printLog("Oplus unfreeze invocation failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    public static void beginFcmDeliveryWindow(String target) {
        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) return;

        sFcmDeliveryWindows.begin(uid, SystemClock.elapsedRealtime());
        sFcmDeliveryPackages.put(uid, target);
        printLog("Oplus FCM delivery window: pkg=" + target + ", uid=" + uid
                + ", duration=" + FCM_DELIVERY_WINDOW_MS + "ms", true);
    }

    static boolean isInFcmDeliveryWindow(int uid) {
        if (sFcmDeliveryWindows.contains(uid, SystemClock.elapsedRealtime())) return true;
        sFcmDeliveryPackages.remove(uid);
        return false;
    }

    private static String getFcmDeliveryPackage(int uid) {
        String packageName = sFcmDeliveryPackages.get(uid);
        return packageName == null ? "uid:" + uid : packageName;
    }

    /** Prevent ColorOS background-network control from closing the target socket mid-push. */
    private void startHookOAppNetControlService() {
        Class<?> serviceClass = XposedHelpers.findClassIfExists(
                OPLUS_APP_NET_CONTROL_SERVICE, classLoader);
        if (serviceClass == null) throw new NoClassDefFoundError(OPLUS_APP_NET_CONTROL_SERVICE);

        int hooks = 0;
        for (Method method : serviceClass.getDeclaredMethods()) {
            if (!"hansUpdateFirewallList".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 0 || !Pair.class.isAssignableFrom(types[0])) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof Pair)) return;
                    Pair<?, ?> update = (Pair<?, ?>) param.args[0];
                    if (!(update.first instanceof Integer) || !(update.second instanceof Boolean)) {
                        return;
                    }
                    int uid = (Integer) update.first;
                    boolean networkRestore = (Boolean) update.second;
                    if (!networkRestore && isInFcmDeliveryWindow(uid)) {
                        // true is the restore/remove-from-firewall branch in ColorOS 16.
                        param.args[0] = Pair.create(uid, true);
                        printLog("Oplus FCM socket-close bypass: pkg="
                                + getFcmDeliveryPackage(uid) + ", uid=" + uid, true);
                    }
                }
            });
            hooks++;
            printLog("Oplus app network-control hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("hansUpdateFirewallList");
    }

    /** Keep Hans from refreezing the just-woken target while it handles the push. */
    private void startHookHansFcmWindow() {
        Class<?> sceneClass = XposedHelpers.findClassIfExists(OPLUS_HANS_SCENE_MANAGER, classLoader);
        if (sceneClass == null) throw new NoClassDefFoundError(OPLUS_HANS_SCENE_MANAGER);

        int hooks = 0;
        for (Method method : sceneClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!"freeze".equals(name)
                    && !"freezeDirectlyForSceneCombo".equals(name)
                    && !"freezeAndTransState".equals(name)
                    && !"freezeViaSM".equals(name)) {
                continue;
            }
            if (method.getParameterTypes().length == 0) continue;
            boolean stateMachineOnly = "freezeViaSM".equals(name)
                    && method.getReturnType() == void.class;
            Object important = stateMachineOnly
                    ? null : findEnumConstant(method.getReturnType(), "IMPORTANT");
            if (!stateMachineOnly && important == null) continue;

            final Object noFreezeResult = important;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || param.args[0] == null) return;
                    int uid = getHansPackageUid(param.args[0]);
                    if (uid >= 0 && isInFcmDeliveryWindow(uid)) {
                        printLog("Oplus FCM Hans-freeze bypass: pkg="
                                + getFcmDeliveryPackage(uid) + ", uid=" + uid
                                + ", method=" + method.getName(), true);
                        param.setResult(noFreezeResult);
                    }
                }
            });
            hooks++;
            printLog("Oplus Hans FCM-window hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("HansSceneManager freeze methods");
    }

    /**
     * The lock-screen Fast Freezer can bypass HansSceneManager's regular freeze methods on
     * cgroup v2 devices. Intercept both the package freeze and the direct UID fast-freeze
     * entry so an in-flight push cannot be suspended through that path either.
     */
    private void startHookHansCGroupFcmWindow() {
        Class<?> cgroupClass = XposedHelpers.findClassIfExists(OPLUS_HANS_CGROUP, classLoader);
        if (cgroupClass == null) throw new NoClassDefFoundError(OPLUS_HANS_CGROUP);

        int hooks = 0;
        for (Method method : cgroupClass.getDeclaredMethods()) {
            if ("hansFreezeLocked".equals(method.getName())
                    && isBooleanType(method.getReturnType())
                    && method.getParameterTypes().length > 0
                    && !method.getParameterTypes()[0].isPrimitive()) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length == 0 || param.args[0] == null) return;
                        int uid = getHansPackageUid(param.args[0]);
                        if (uid >= 0 && isInFcmDeliveryWindow(uid)) {
                            printLog("Oplus FCM cgroup-freeze bypass: pkg="
                                    + getFcmDeliveryPackage(uid) + ", uid=" + uid, true);
                            param.setResult(false);
                        }
                    }
                });
                hooks++;
                printLog("Oplus Hans cgroup hook active: " + describeMethod(method));
            } else if (isFastFreezeEnter(method)) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int uid = (Integer) param.args[0];
                        if (isInFcmDeliveryWindow(uid)) {
                            printLog("Oplus FCM fast-freeze bypass: pkg="
                                    + getFcmDeliveryPackage(uid) + ", uid=" + uid, true);
                            param.setResult(null);
                        }
                    }
                });
                hooks++;
                printLog("Oplus Hans fast-freezer hook active: " + describeMethod(method));
            }
        }
        if (hooks == 0) throw new NoSuchMethodError("HansCGroup freeze methods");
    }

    private static boolean isFastFreezeEnter(Method method) {
        String[] types = java.util.Arrays.stream(method.getParameterTypes())
                .map(Class::getName).toArray(String[]::new);
        return com.kooritea.fcmfix.util.HansSignature.isFastFreezeEnter(
                method.getName(), method.getReturnType().getName(), types);
    }

    private int getHansPackageUid(Object hansPackage) {
        try {
            Object uid = XposedHelpers.callMethod(hansPackage, "getUid");
            return uid instanceof Integer ? (Integer) uid : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Object[] createUnfreezeArguments(Class<?>[] types, int uid) {
        Object[] args = new Object[types.length];
        boolean uidAssigned = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == int.class || type == Integer.class) {
                args[i] = uidAssigned ? 0 : uid;
                uidAssigned = true;
            } else if (WorkSource.class.isAssignableFrom(type)) {
                args[i] = new WorkSource();
            } else if (type == String.class || CharSequence.class.isAssignableFrom(type)) {
                args[i] = "FCMFix";
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false;
            } else if (type == long.class || type == Long.class) {
                args[i] = 0L;
            } else if (type == float.class || type == Float.class) {
                args[i] = 0F;
            } else if (type == double.class || type == Double.class) {
                args[i] = 0D;
            } else if (!type.isPrimitive()) {
                args[i] = null;
            } else {
                return null;
            }
        }
        return uidAssigned ? args : null;
    }

    private void startHookRegisterGmsRestrictObserver() {
        int hooks = hookAllMethods("com.android.server.hans.scene.OplusBgSceneManager",
                "registerGmsRestrictObserver", null);
        if (hooks == 0) throw new NoSuchMethodError("registerGmsRestrictObserver");
    }

    private void startHookUpdateGmsRestrict() {
        int hooks = hookAllMethods("com.android.server.hans.scene.OplusBgSceneManager",
                "updateGmsRestrict", null);
        if (hooks == 0) throw new NoSuchMethodError("updateGmsRestrict");
    }

    private void startHookIsGoogleRestricInfoOn() {
        int hooks = hookAllMethods(OPLUS_STARTUP_STRATEGY,
                "isGoogleRestricInfoOn", Boolean.FALSE);
        if (hooks == 0) throw new NoSuchMethodError("isGoogleRestricInfoOn");
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

    /**
     * The CN ELSA policy gives GMS/GSF/Play a broad Hans prevent mask. Returning the
     * framework's NOT_PROXY result here is the runtime equivalent of clearing that mask,
     * without changing the XML or weakening restrictions for unrelated packages.
     */
    private void startHookHansGmsRestriction() {
        Class<?> configClass = XposedHelpers.findClassIfExists(OPLUS_HANS_DB_CONFIG, classLoader);
        if (configClass == null) throw new NoClassDefFoundError(OPLUS_HANS_DB_CONFIG);

        int hooks = 0;
        for (Method method : configClass.getDeclaredMethods()) {
            if (!"isSysRestrictionCpn".equals(method.getName())) continue;
            Object notProxy = findEnumConstant(method.getReturnType(), "NOT_PROXY");
            if (notProxy == null) continue;

            final Object finalNotProxy = notProxy;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) return;
                    String packageName = (String) param.args[0];
                    if (isGoogleCorePackage(packageName)) {
                        printLog("Oplus Hans GMS restriction bypass: pkg=" + packageName, true);
                        param.setResult(finalNotProxy);
                    }
                }
            });
            hooks++;
            printLog("Oplus Hans restriction hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("isSysRestrictionCpn");
    }

    private int hookAllMethods(String className, String methodName, Object result) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) return 0;
        int hooks = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (result == null && method.getReturnType() != void.class) {
                continue;
            }
            if (result == Boolean.FALSE && method.getReturnType() != boolean.class
                    && method.getReturnType() != Boolean.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(result);
                }
            });
            hooks++;
            printLog("Oplus restriction hook active: " + describeMethod(method));
        }
        return hooks;
    }

    private String getIntentTarget(Intent intent) {
        if (intent.getComponent() != null) {
            return intent.getComponent().getPackageName();
        }
        return intent.getPackage();
    }

    private String findAllowedPackageArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof String && targetIsAllow((String) arg)) {
                return (String) arg;
            }
        }
        return null;
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

    private boolean isBooleanType(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    private boolean isGoogleCorePackage(String packageName) {
        return "com.google.android.gms".equals(packageName)
                || "com.google.android.gsf".equals(packageName)
                || "com.android.vending".equals(packageName);
    }

    private Object findEnumConstant(Class<?> type, String wantedName) {
        if (!type.isEnum()) return null;
        Object[] constants = type.getEnumConstants();
        if (constants == null) return null;
        for (Object constant : constants) {
            if (wantedName.equals(((Enum<?>) constant).name())) return constant;
        }
        return null;
    }

    private Intent findIntentArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        for (Object arg : args) {
            if (arg == null) continue;
            try {
                Object nestedIntent = XposedHelpers.getObjectField(arg, "intent");
                if (nestedIntent instanceof Intent) {
                    return (Intent) nestedIntent;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object getNoProxyResult(Class<?> returnType) {
        if (returnType == boolean.class || returnType == Boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType.isEnum()) {
            Object[] constants = returnType.getEnumConstants();
            if (constants != null) {
                String[] preferred = new String[]{"NOT_INCLUDE", "NOT_PROXY", "ALLOW", "PASS"};
                for (String name : preferred) {
                    for (Object constant : constants) {
                        if (name.equals(((Enum<?>) constant).name())) {
                            return constant;
                        }
                    }
                }
            }
        }
        return UnsupportedResult.VALUE;
    }

    private enum UnsupportedResult { VALUE }

    private static String describeMethod(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) result.append(',');
            result.append(types[i].getSimpleName());
        }
        return result.append("): ").append(method.getReturnType().getSimpleName()).toString();
    }
}
