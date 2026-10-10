package com.kooritea.fcmfix.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
    /** The lines gzipped by pack(): the broadcast result crosses binder as a oneway call. */
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

    public static byte[] pack(List<String> lines) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    public static List<String> unpack(byte[] packed) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) text.write(buffer, 0, read);
        }
        String joined = text.toString(StandardCharsets.UTF_8.name());
        return joined.isEmpty() ? new ArrayList<>() : Arrays.asList(joined.split("\n", -1));
    }
}
