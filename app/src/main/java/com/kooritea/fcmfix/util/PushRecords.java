package com.kooritea.fcmfix.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Last trusted FCM push per package since boot, for the in-app self-check. Bounded LRU. */
public final class PushRecords {
    private final int capacity;
    private final LinkedHashMap<String, long[]> records;

    public PushRecords(int capacity) {
        this.capacity = capacity;
        this.records = new LinkedHashMap<String, long[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                return size() > PushRecords.this.capacity;
            }
        };
    }

    public synchronized void record(String packageName, long wallTime) {
        if (packageName == null) return;
        long[] record = records.get(packageName);
        if (record == null) records.put(packageName, new long[]{wallTime, 1});
        else {
            record[0] = wallTime;
            record[1]++;
        }
    }

    /** {last push wall time, push count}, or null when no push was seen since boot. */
    public synchronized long[] get(String packageName) {
        long[] record = records.get(packageName);
        return record == null ? null : record.clone();
    }
}
