package com.kooritea.fcmfix.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One immutable view of one RemotePreferences read; partial updates are never published. */
public final class ConfigSnapshot {
    public static final String AUTO_ALLOW_FCM = "autoAllowFcm";

    /** Apps the user ticked by hand. */
    public final Set<String> allowList;
    /** FCM apps the user unticked while auto-allow is on; they stay out of the automatic set. */
    public final Set<String> excludeList;
    public final Map<String, Boolean> options;

    public ConfigSnapshot(Map<String, ?> values) {
        allowList = packageSet(values, "allowList");
        excludeList = packageSet(values, "excludeList");
        Map<String, Boolean> flags = new HashMap<>();
        for (String key : new String[]{"disableAutoCleanNotification", "includeIceBoxDisableApp", AUTO_ALLOW_FCM}) {
            Object value = values.get(key);
            if (value != null && !(value instanceof Boolean)) throw new IllegalArgumentException("Invalid " + key);
            flags.put(key, Boolean.TRUE.equals(value));
        }
        options = Collections.unmodifiableMap(flags);
    }

    public boolean autoAllowFcm() {
        return options.get(AUTO_ALLOW_FCM);
    }

    /** Whether a push to this package is let through; fcmPackages are the installed FCM apps. */
    public boolean allows(String packageName, Set<String> fcmPackages) {
        if (packageName == null) return false;
        if (allowList.contains(packageName)) return true;
        return autoAllowFcm() && !excludeList.contains(packageName) && fcmPackages.contains(packageName);
    }

    private static Set<String> packageSet(Map<String, ?> values, String key) {
        Set<String> packages = new HashSet<>();
        Object raw = values.get(key);
        if (raw != null) {
            if (!(raw instanceof Set<?>)) throw new IllegalArgumentException("Invalid " + key);
            for (Object name : (Set<?>) raw) {
                if (!(name instanceof String)) throw new IllegalArgumentException("Invalid package name");
                packages.add((String) name);
            }
        }
        return Collections.unmodifiableSet(packages);
    }
}
