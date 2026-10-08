package com.kooritea.fcmfix.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-process result of each hook group, in install order, for the in-app status query. */
public final class HookStatus {
    /** Ordered broadcast from the module app; each hooked process adds its result extras. */
    public static final String QUERY_ACTION_SUFFIX = ".query.status";
    public static final String ACTIVE_SUFFIX = ".active";
    public static final String FAILED_SUFFIX = ".failed";
    /** system_server only: false during the first minute after unlock, when FCM is not exempted. */
    public static final String KEY_SYSTEM_READY = "android.ready";
    /** system_server only: whether Battery declares IgnoreGmsUserSet (gates the firewall drop). */
    public static final String KEY_IGNORE_GMS_USER_SET = "ignoreGmsUserSet";

    private final Map<String, String> failures = new LinkedHashMap<>();

    public synchronized void recordActive(String name) {
        failures.put(name, null);
    }

    public synchronized void recordFailed(String name, String reason) {
        failures.put(name, reason == null ? "unknown" : reason);
    }

    public synchronized ArrayList<String> active() {
        ArrayList<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : failures.entrySet()) {
            if (entry.getValue() == null) result.add(entry.getKey());
        }
        return result;
    }

    /** "name: reason" for every group that did not install. */
    public synchronized ArrayList<String> failed() {
        ArrayList<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : failures.entrySet()) {
            if (entry.getValue() != null) result.add(entry.getKey() + ": " + entry.getValue());
        }
        return result;
    }
}
