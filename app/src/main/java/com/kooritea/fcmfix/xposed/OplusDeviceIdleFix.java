package com.kooritea.fcmfix.xposed;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.List;

/** Restores only the Google entries omitted by the ColorOS CN regional Doze list. */
public class OplusDeviceIdleFix extends XposedModule {

    private static final String OPLUS_DEVICE_IDLE_HELPER =
            "com.android.server.OplusDeviceIdleHelper";
    private static final String OPLUS_GOOGLE_RESTRICTION_HELPER =
            "com.android.server.OplusGoogleRestrictionHelper";
    private static final String OPLUS_DEEP_SLEEP_HELPER =
            "com.android.server.alarm.OplusDeepSleepHelper";
    private static final String[] GOOGLE_DOZE_PACKAGES = new String[]{
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending"
    };

    public OplusDeviceIdleFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHook();
        } catch (Throwable e) {
            printLog("hook error OplusDeviceIdleFix: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            startHookGoogleAlarmRestrict();
        } catch (Throwable e) {
            printLog("hook error Oplus Google alarm restrict: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            startHookDeepSleepAlarm();
        } catch (Throwable e) {
            printLog("hook error Oplus deep-sleep alarm: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * During battery deep sleep, alarms matching a network rule are held until the network is
     * restored. The battery hook keeps GMS on the deep-sleep network whitelist (like HeyTap
     * push), so its alarms must not wait for a restore that it does not need.
     */
    private void startHookDeepSleepAlarm() {
        Class<?> helperClass = XposedHelpers.findClassIfExists(OPLUS_DEEP_SLEEP_HELPER, classLoader);
        if (helperClass == null) throw new NoClassDefFoundError(OPLUS_DEEP_SLEEP_HELPER);

        int hooks = 0;
        for (Method method : helperClass.getDeclaredMethods()) {
            String name = method.getName();
            if ((!"filterDeepSleepAlarm".equals(name) && !"ruleMatchDeepSleepAlarm".equals(name))
                    || method.getReturnType() != boolean.class || method.getParameterTypes().length != 1) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] == null) return;
                    Object packageName = XposedHelpers.getObjectField(param.args[0], "packageName");
                    if (GOOGLE_DOZE_PACKAGES[0].equals(packageName) || GOOGLE_DOZE_PACKAGES[1].equals(packageName)) {
                        param.setResult(false);
                    }
                }
            });
            hooks++;
            printLog("Oplus deep-sleep alarm hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError(OPLUS_DEEP_SLEEP_HELPER + "#filterDeepSleepAlarm");
    }

    /**
     * ColorOS 17 OplusGoogleAlarmRestrict turns GMS *_WAKEUP alarms into non-wakeup ones
     * while this helper reports Google as restricted, which stalls the FCM heartbeat in
     * Doze. The battery-side hook clears the source broadcast; this covers a missing
     * battery scope. Reporting "not restricted" also restores already-downgraded alarms.
     */
    private void startHookGoogleAlarmRestrict() {
        Class<?> helperClass = XposedHelpers.findClassIfExists(
                OPLUS_GOOGLE_RESTRICTION_HELPER, classLoader);
        if (helperClass == null) throw new NoClassDefFoundError(OPLUS_GOOGLE_RESTRICTION_HELPER);

        int hooks = 0;
        for (Method method : helperClass.getDeclaredMethods()) {
            if (!"isGoogleRestrct".equals(method.getName())
                    || method.getParameterTypes().length != 0
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            hooks++;
            printLog("Oplus Google alarm restriction hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError(OPLUS_GOOGLE_RESTRICTION_HELPER + "#isGoogleRestrct");
    }

    private void startHook() {
        Class<?> helperClass = XposedHelpers.findClassIfExists(
                OPLUS_DEVICE_IDLE_HELPER, classLoader);
        if (helperClass == null) {
            throw new NoClassDefFoundError(OPLUS_DEVICE_IDLE_HELPER);
        }

        int whitelistHooks = 0;
        int restrictSwitchHooks = 0;
        for (Method method : helperClass.getDeclaredMethods()) {
            // getNewWhiteList is a short private helper and may be inlined into updateWhiteList;
            // whiteListChangedHandle receives the same list and is too large to inline.
            if ("getNewWhiteList".equals(method.getName())
                    || "whiteListChangedHandle".equals(method.getName())) {
                final boolean before = "whiteListChangedHandle".equals(method.getName());
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (before) restoreGoogleEntries(findListArgument(param.args));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (before) return;
                        List<String> whiteList = findListArgument(param.args);
                        if (whiteList == null && param.getResult() instanceof List) {
                            whiteList = (List<String>) param.getResult();
                        }
                        restoreGoogleEntries(whiteList);
                    }
                });
                whitelistHooks++;
                printLog("Oplus Doze whitelist hook active: " + describeMethod(method));
            } else if ("getGoogleRestrictSwitch".equals(method.getName())
                    && (method.getReturnType() == boolean.class
                    || method.getReturnType() == Boolean.class)) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(false);
                    }
                });
                restrictSwitchHooks++;
                printLog("Oplus Doze Google restriction hook active: " + describeMethod(method));
            }
        }
        if (whitelistHooks == 0) throw new NoSuchMethodError("getNewWhiteList/whiteListChangedHandle");
        if (restrictSwitchHooks == 0) {
            printLog("OplusDeviceIdleHelper#getGoogleRestrictSwitch not found");
        }
    }

    private void restoreGoogleEntries(List<String> whiteList) {
        if (whiteList == null) return;
        for (String packageName : GOOGLE_DOZE_PACKAGES) {
            if (!whiteList.contains(packageName)) {
                whiteList.add(packageName);
                printLog("Oplus Doze whitelist restored: " + packageName, true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> findListArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof List) return (List<String>) arg;
        }
        return null;
    }

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
