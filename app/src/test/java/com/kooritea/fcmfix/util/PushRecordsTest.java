package com.kooritea.fcmfix.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PushRecordsTest {
    @Test public void countsAndKeepsLatestTime() {
        PushRecords records = new PushRecords(4);
        assertNull(records.get("chat"));
        records.record("chat", 100);
        records.record("chat", 200);
        assertArrayEquals(new long[]{200, 2}, records.get("chat"));
    }
    @Test public void evictsLeastRecentlyPushed() {
        PushRecords records = new PushRecords(2);
        records.record("a", 1);
        records.record("b", 2);
        records.record("a", 3);
        records.record("c", 4);
        assertNull(records.get("b"));
        assertNotNull(records.get("a"));
        assertNotNull(records.get("c"));
    }
    @Test public void returnedRecordIsACopy() {
        PushRecords records = new PushRecords(2);
        records.record("a", 1);
        records.get("a")[1] = 99;
        assertEquals(1, records.get("a")[1]);
    }
}
