package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.content.pm.PackageManager;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.lang.reflect.Method;

import static com.kooritea.fcmfix.xposed.OplusHooks.runHook;

/**
 * ColorOS Battery's GoogleRestrictionController applies POLICY_REJECT_ALL when its
 * Google connectivity probe fails. This intercepts every matching Google UID reject-all
 * write inside Battery's process (com.oplus.athena, shared with Athena), not exclusively that
 * controller's call site. Calls made directly by Settings/TrafficMonitor are outside this process.
 */
public class OplusBatteryNetworkFix extends XposedModule {

    private static final String NETWORK_CONTROL_MANAGER =
            "android.net.OplusNetworkingControlManager";
    private static final String GOOGLE_RESTRICT_CHANGE = "oplus.intent.action.google_restrict_change";
    private static final String EXTRA_RESTRICT_ENABLE = "restrict_enable";
    private static final String USER_CHANGE_GMS_NETWORK_CONTROL = "oplus_user_change_gms_network_control";
    private static final String IGNORE_GMS_USER_SET = "IgnoreGmsUserSet";
    private static final String BATTERY_PACKAGE = "com.oplus.battery";
    private static final String DEEP_SLEEP_CONTROLLER = "com.oplus.deepsleep.ControllerCenter";
    private static final int POLICY_REJECT_ALL = 4;
    private static final int POLICY_NONE = 0;
    private static final String[] GOOGLE_NETWORK_PACKAGES = new String[]{
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.configupdater"
    };

    public OplusBatteryNetworkFix(ClassLoader classLoader) {
        super(classLoader);
        runHook("Oplus Battery GMS network policy", this::startHookGoogleNetworkPolicy);
        runHook("Oplus Battery Google restrict broadcast", this::startHookGoogleRestrictBroadcast);
        runHook("Oplus Battery GMS user-change flag", this::startHookIgnoredUserChange);
        runHook("Oplus Battery deep-sleep network whitelist", this::startHookDeepSleepNetworkWhitelist);
    }

    /**
     * Battery deep sleep cuts the network for everything except its whitelists, which keep
     * HeyTap push (com.heytap.mcs), VoWiFi, P2P and opted-in IM apps online. Give the GMS UID
     * (shared with GSF) the same treatment so FCM is not the only push channel dropped at night.
     * Both the package and the UID whitelist builders are covered; entries are UID strings.
     */
    private void startHookDeepSleepNetworkWhitelist() {
        Class<?> controllerClass = XposedHelpers.findClassIfExists(DEEP_SLEEP_CONTROLLER, classLoader);
        if (controllerClass == null) throw new NoClassDefFoundError(DEEP_SLEEP_CONTROLLER);

        int hooks = 0;
        for (Method method : controllerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if ((!"addPkgWhiteArray".equals(method.getName()) && !"addUidWhiteArray".equals(method.getName()))
                    || parameters.length == 0 || !java.util.List.class.isAssignableFrom(parameters[0])) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof java.util.List) || context == null) return;
                    int uid;
                    try {
                        uid = context.getPackageManager().getPackageUid(GOOGLE_NETWORK_PACKAGES[0], 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        return;
                    }
                    java.util.List<String> whitelist = (java.util.List<String>) param.args[0];
                    String entry = String.valueOf(uid);
                    if (!whitelist.contains(entry)) {
                        whitelist.add(entry);
                        printLog("Oplus Battery deep-sleep network whitelist: GMS uid=" + uid, true);
                    }
                }
            });
            hooks++;
            printLog("Oplus Battery deep-sleep whitelist hook active: " + method);
        }
        if (hooks == 0) throw new NoSuchMethodError(DEEP_SLEEP_CONTROLLER + "#addPkgWhiteArray/addUidWhiteArray");
    }

    /**
     * GoogleRestrictionController skips packages flagged in oplus_user_change_gms_network_control,
     * so a stale reject-all from an older UI survives every boot. When the battery APK declares
     * IgnoreGmsUserSet=true (ColorOS 17), Traffic Monitor hides these toggles and refuses user
     * changes, so the flag can no longer reflect a user choice. Only then report it as unset,
     * letting the controller rewrite the policy (which the hook above turns into POLICY_NONE).
     */
    private void startHookIgnoredUserChange() {
        Class<?> global = XposedHelpers.findClass("android.provider.Settings$Global", classLoader);
        int hooks = 0;
        for (Method method : global.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"getInt".equals(method.getName()) || parameters.length < 2
                    || parameters[1] != String.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!USER_CHANGE_GMS_NETWORK_CONTROL.equals(param.args[1])
                            || !batteryIgnoresGmsUserSet()) {
                        return;
                    }
                    param.setResult(0);
                    logOnce("Oplus Battery ignores stale GMS user-change flag (IgnoreGmsUserSet)");
                }
            });
            hooks++;
        }
        if (hooks == 0) throw new NoSuchMethodError("Settings.Global#getInt(ContentResolver,String...)");
        printLog("Oplus Battery GMS user-change hooks active: " + hooks);
    }

    private static volatile Boolean ignoresGmsUserSet;

    /**
     * True when Traffic Monitor hides the Google network toggles, so no Google network
     * policy can come from the user. Lookup failures are not cached.
     */
    static boolean batteryIgnoresGmsUserSet() {
        Boolean cached = ignoresGmsUserSet;
        if (cached != null) return cached;
        if (context == null) return false;
        boolean ignores;
        try {
            android.os.Bundle metaData = context.getPackageManager()
                    .getApplicationInfo(BATTERY_PACKAGE, PackageManager.GET_META_DATA).metaData;
            Object value = metaData == null ? null : metaData.get(IGNORE_GMS_USER_SET);
            // Traffic Monitor compares the value's string form with "true".
            ignores = value != null && "true".equals(value.toString());
        } catch (Throwable e) {
            printLog("Cannot read " + IGNORE_GMS_USER_SET + ": " + e.getMessage());
            return false;
        }
        ignoresGmsUserSet = ignores;
        return ignores;
    }

    /**
     * The same failed Google probe also broadcasts google_restrict_change. On ColorOS 17
     * system_server then downgrades GMS wakeup alarms (OplusGoogleAlarmRestrict) and puts
     * Google packages in the RARE standby bucket, so the FCM heartbeat/reconnect stops in
     * Doze. Clear only the restrict_enable=true flag; list updates pass through unchanged.
     */
    private void startHookGoogleRestrictBroadcast() {
        Class<?> contextImpl = XposedHelpers.findClass("android.app.ContextImpl", classLoader);
        int hooks = 0;
        for (Method method : contextImpl.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().startsWith("sendBroadcast")
                    || parameters.length == 0 || parameters[0] != Intent.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if (intent == null || !GOOGLE_RESTRICT_CHANGE.equals(intent.getAction())
                            || !intent.getBooleanExtra(EXTRA_RESTRICT_ENABLE, false)) {
                        return;
                    }
                    intent.putExtra(EXTRA_RESTRICT_ENABLE, false);
                    printLog("Oplus Battery Google restrict broadcast cleared", true);
                }
            });
            hooks++;
        }
        if (hooks == 0) throw new NoSuchMethodError("ContextImpl#sendBroadcast(Intent...)");
        printLog("Oplus Battery Google restrict broadcast hooks active: " + hooks);
    }

    private void startHookGoogleNetworkPolicy() {
        Class<?> managerClass = XposedHelpers.findClassIfExists(
                NETWORK_CONTROL_MANAGER, classLoader);
        if (managerClass == null) {
            throw new NoClassDefFoundError(NETWORK_CONTROL_MANAGER);
        }

        int hooks = 0;
        for (Method method : managerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"setUidPolicy".equals(method.getName())
                    || parameters.length != 2
                    || parameters[0] != int.class
                    || parameters[1] != int.class) {
                continue;
            }

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int uid = (Integer) param.args[0];
                    int policy = (Integer) param.args[1];
                    if (policy != POLICY_REJECT_ALL || !isGoogleNetworkUid(uid)) {
                        return;
                    }

                    // Let the original method clear any stale reject-all state in netd.
                    param.args[1] = POLICY_NONE;
                    printLog("Oplus Battery GMS network reject bypass: uid=" + uid, true);
                }
            });
            hooks++;
            printLog("Oplus Battery network hook active: " + method);
        }

        if (hooks == 0) {
            throw new NoSuchMethodError(NETWORK_CONTROL_MANAGER + "#setUidPolicy(int,int)");
        }
    }

    private boolean isGoogleNetworkUid(int uid) {
        if (context == null) {
            printLog("Oplus Battery network hook skipped before context initialization");
            return false;
        }

        PackageManager packageManager = context.getPackageManager();
        for (String packageName : GOOGLE_NETWORK_PACKAGES) {
            try {
                if (packageManager.getPackageUid(packageName, 0) == uid) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }
}
