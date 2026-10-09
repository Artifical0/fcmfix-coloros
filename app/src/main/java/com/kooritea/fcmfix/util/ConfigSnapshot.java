package com.kooritea.fcmfix.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One immutable view of one RemotePreferences read; partial updates are never published. */
public final class ConfigSnapshot {
    public final Set<String> allowList;
    public final Map<String, Boolean> options;

    public ConfigSnapshot(Map<String, ?> values) {
        Set<String> packages = new HashSet<>();
        Object raw = values.get("allowList");
        if (raw != null) {
            if (!(raw instanceof Set<?>)) throw new IllegalArgumentException("Invalid allowList");
            for (Object name : (Set<?>) raw) {
                if (!(name instanceof String)) throw new IllegalArgumentException("Invalid package name");
                packages.add((String) name);
            }
        }
        allowList = Collections.unmodifiableSet(packages);
        Map<String, Boolean> flags = new HashMap<>();
        for (String key : new String[]{"disableAutoCleanNotification", "includeIceBoxDisableApp"}) {
            Object value = values.get(key);
            if (value != null && !(value instanceof Boolean)) throw new IllegalArgumentException("Invalid " + key);
            flags.put(key, Boolean.TRUE.equals(value));
        }
        options = Collections.unmodifiableMap(flags);
    }
}
