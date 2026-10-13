package com.kooritea.fcmfix.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Signatures and narrow policy guards checked against the local ColorOS 16 firmware. */
public final class GoogleNetworkCompat {
    private GoogleNetworkCompat() {}

    public static boolean isNetworkWhitelist(Method method) {
        return "networkDisableWhiteList".equals(method.getName())
                && method.getReturnType() == int.class
                && Arrays.equals(method.getParameterTypes(), new Class<?>[]{List.class, int.class});
    }

    public static boolean isSceneAction(Method method) {
        if (!takesBundle(method)) return false;
        return ("requestSceneAction".equals(method.getName()) && method.getReturnType() == void.class)
                || ("requestSceneActionSync".equals(method.getName()) && method.getReturnType() == boolean.class);
    }

    private static boolean takesBundle(Method method) {
        Class<?>[] types = method.getParameterTypes();
        return types.length == 1 && "android.os.Bundle".equals(types[0].getName());
    }

    public static boolean shouldSkipGmsForceStop(String pkg, String reason, String subAction, int strategy) {
        return "com.google.android.gms".equals(pkg)
                && "DeepSleepLogicDisNetRestore".equals(reason)
                && "CommonExternalClean".equals(subAction) && strategy == 2;
    }

    /**
     * PLK110 16.0.10.500's manager only implements Bluetooth control. Do not hide a changed
     * weak-signal signature on another ROM: require the known old shape, Android 16, and no
     * weak-signal/firewall members. A missing class or an unfamiliar shape remains a failure.
     */
    public static boolean isLegacyConnectivityWithoutWeakSignal(Class<?> type, int sdk) {
        if (sdk != 36 || type == null || type.getSuperclass() != Object.class) return false;
        for (Method method : type.getDeclaredMethods()) {
            if (isNetworkRestrictionMember(method.getName())) return false;
        }
        for (Field field : type.getDeclaredFields()) {
            if (isNetworkRestrictionMember(field.getName())) return false;
        }
        try {
            return type.getDeclaredMethod("btFreezeControlEnable").getReturnType() == boolean.class
                    && type.getDeclaredMethod("isBtFreezeDozeRestrict", int.class, String.class)
                            .getReturnType() == boolean.class
                    && type.getDeclaredField("mBtFreezeDozeRestrict").getType() == boolean.class
                    && type.getDeclaredField("mBtConnectionControlPkgList").getType() == ArrayList.class;
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            return false;
        }
    }

    /**
     * PJZ110 16.0.3.502 (issue #5) has no OplusHansConnectivityManager at all, and its
     * OplusHansManager has no weak-signal scene (ColorOS 17 declares STRICT_MODE_SCENE_WEAK_SIGNAL
     * there). Only that combination says the restriction is absent rather than moved.
     */
    public static boolean isHansWithoutWeakSignal(Class<?> hansManager) {
        if (hansManager == null) return false;
        for (Method method : hansManager.getDeclaredMethods()) {
            if (method.getName().toLowerCase(Locale.ROOT).contains("weak")) return false;
        }
        for (Field field : hansManager.getDeclaredFields()) {
            if (field.getName().toLowerCase(Locale.ROOT).contains("weak")) return false;
        }
        return true;
    }

    /**
     * Without MaliciousRestrictPolicy, PJZ110 16.0.3.502 checks the malicious component list only
     * in OplusAppStartupManager.preventByMaliciousCompStartegy(int,int,String,String), called from
     * shouldPreventStartActivity alone: activities, not broadcasts or services. Require that
     * method and no other malicious member besides dump, so a moved broadcast/service check stays a failure.
     */
    public static boolean isActivityOnlyMaliciousCheck(Class<?> startupManager) {
        if (startupManager == null) return false;
        boolean activityCheck = false;
        for (Method method : startupManager.getDeclaredMethods()) {
            String name = method.getName();
            // dumpMaliciousConfig(PrintWriter) only prints the list for dumpsys.
            if (!name.toLowerCase(Locale.ROOT).contains("malicious") || name.startsWith("dump")) continue;
            if (!"preventByMaliciousCompStartegy".equals(name) || method.getReturnType() != boolean.class
                    || !Arrays.equals(method.getParameterTypes(),
                            new Class<?>[]{int.class, int.class, String.class, String.class})) {
                return false;
            }
            activityCheck = true;
        }
        return activityCheck;
    }

    private static boolean isNetworkRestrictionMember(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("weak") || lower.contains("firewall");
    }
}
