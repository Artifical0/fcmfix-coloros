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
    /** system_server only: whether Battery declares IgnoreGmsUserSet (gates the firewall drop). */
    public static final String KEY_IGNORE_GMS_USER_SET = "ignoreGmsUserSet";
    /** Query extra: also return each process's recent module log lines (report export only). */
    public static final String EXTRA_LOGS = "logs";
    public static final String LOGS_SUFFIX = ".logs";

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
