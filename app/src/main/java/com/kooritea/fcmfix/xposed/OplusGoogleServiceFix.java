package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.content.pm.PackageManager;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.NightNetworkWhitelist;

import java.lang.reflect.Method;

import static com.kooritea.fcmfix.util.HookResults.findEnumConstant;
import static com.kooritea.fcmfix.xposed.OplusHooks.*;

/**
 * Keeps the Google core services (GMS, GSF, Play Store) online and unrestricted in
 * system_server: ColorOS "Google restricted" state, Hans GMS masks, weak-signal and deep-sleep
 * network cuts, the battery's post-unlock firewall rule and the deep-sleep GMS force-stop.
 */
public class OplusGoogleServiceFix extends XposedModule {

    private static final String OPLUS_STARTUP_STRATEGY =
            "com.android.server.am.OplusAppStartupManager$OplusStartupStrategy";
    private static final String OPLUS_HANS_DB_CONFIG =
            "com.android.server.hans.OplusHansDBConfig";
    private static final String OPLUS_APP_NET_CONTROL_SERVICE =
            "com.android.server.nwpower.OAppNetControlService";
    private static final String OPLUS_BG_SCENE_MANAGER =
            "com.android.server.hans.scene.OplusBgSceneManager";
    private static final String OPLUS_HANS_CONNECTIVITY_MANAGER =
            "com.android.server.hans.device.OplusHansConnectivityManager";
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

    public OplusGoogleServiceFix(ClassLoader classLoader) {
        super(classLoader);
        runHook("registerGmsRestrictObserver", this::startHookRegisterGmsRestrictObserver);
        runHook("updateGmsRestrict", this::startHookUpdateGmsRestrict);
        runHook("isGoogleRestricInfoOn", this::startHookIsGoogleRestricInfoOn);
        runHook("isSysRestrictionCpn", this::startHookHansGmsRestriction);
        runHook("isGmsRestricted", this::startHookIsGmsRestricted);
        runHook("weak-signal net whitelist", this::startHookWeakSignalNetWhiteList);
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
                    java.util.List<Object> whitelist =
                            NightNetworkWhitelist.withUid((java.util.List<?>) param.args[0], uid);
                    if (whitelist == null) return;
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
                            OplusGoogleServiceFix::requestGmsReconnect, 3000);
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

    private boolean isGoogleCorePackage(String packageName) {
        return "com.google.android.gms".equals(packageName)
                || "com.google.android.gsf".equals(packageName)
                || "com.android.vending".equals(packageName);
    }
}
