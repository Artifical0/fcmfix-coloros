package com.kooritea.fcmfix.util;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LogRingTest {
    @Test public void keepsNewestLinesInOrder() {
        LogRing ring = new LogRing(3);
        ring.add("a");
        ring.add("b");
        assertEquals(Arrays.asList("a", "b"), ring.snapshot());
        ring.add("c");
        ring.add("d");
        ring.add("e");
        assertEquals(Arrays.asList("…（更早的 2 条已丢弃）", "c", "d", "e"), ring.snapshot());
    }
    @Test public void snapshotIsACopy() {
        LogRing ring = new LogRing(2);
        ring.add("a");
        ring.snapshot().clear();
        assertEquals(1, ring.snapshot().size());
    }
}
