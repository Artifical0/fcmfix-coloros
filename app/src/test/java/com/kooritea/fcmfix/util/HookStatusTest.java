package com.kooritea.fcmfix.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HookStatusTest {
    @Test public void groupsAreReportedInInstallOrder() {
        HookStatus status = new HookStatus();
        status.recordActive("b");
        status.recordFailed("a", "NoSuchMethodError: x");
        status.recordActive("c");
        assertEquals(List.of("b", "c"), status.active());
        assertEquals(List.of("a: NoSuchMethodError: x"), status.failed());
    }

    @Test public void laterResultReplacesEarlierOne() {
        HookStatus status = new HookStatus();
        status.recordFailed("a", "first");
        status.recordActive("a");
        assertEquals(List.of("a"), status.active());
        assertTrue(status.failed().isEmpty());
    }

    @Test public void missingReasonIsStillAFailure() {
        HookStatus status = new HookStatus();
        status.recordFailed("a", null);
        assertEquals(List.of("a: unknown"), status.failed());
    }

    @Test public void logsSurvivePacking() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1000; i++) lines.add("10-10 08:28:34.282 [fcmfix] [android]Oplus FCM delivery window: pkg=" + i);
        byte[] packed = HookStatus.pack(lines);
        assertTrue(packed.length < 40_000);
        assertEquals(lines, HookStatus.unpack(packed));
        assertTrue(HookStatus.unpack(HookStatus.pack(List.of())).isEmpty());
    }
}
