package com.kooritea.fcmfix.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class SelfCheckTest {
    @Test public void gmsPolicyNamesEachRestriction() {
        assertEquals(SelfCheck.Level.OK, SelfCheck.gmsPolicy(0).level);
        assertEquals(SelfCheck.Level.UNKNOWN, SelfCheck.gmsPolicy(SelfCheck.UNKNOWN).level);
        assertTrue(SelfCheck.gmsPolicy(4).text.startsWith("全部禁止"));
        assertTrue(SelfCheck.gmsPolicy(1).text.startsWith("禁止移动数据"));
        assertTrue(SelfCheck.gmsPolicy(2).text.startsWith("禁止 Wi‑Fi"));
        assertTrue(SelfCheck.gmsPolicy(3).text.startsWith("禁止 Wi‑Fi 和移动数据"));
        assertEquals(SelfCheck.Level.FAIL, SelfCheck.gmsPolicy(4).level);
    }
    @Test public void gmsBucketFailsFromRare() {
        assertEquals(SelfCheck.Level.OK, SelfCheck.gmsBucket(5).level);
        assertEquals(SelfCheck.Level.OK, SelfCheck.gmsBucket(30).level);
        assertEquals(SelfCheck.Level.FAIL, SelfCheck.gmsBucket(40).level);
        assertEquals(SelfCheck.Level.UNKNOWN, SelfCheck.gmsBucket(SelfCheck.UNKNOWN).level);
    }
    @Test public void configSyncComparesBothSides() {
        assertEquals(SelfCheck.Level.FAIL, SelfCheck.configSync(false, 0, false, 0, false).level);
        assertEquals(SelfCheck.Level.WARN, SelfCheck.configSync(true, 2, false, 3, false).level);
        assertEquals(SelfCheck.Level.WARN, SelfCheck.configSync(true, 3, false, 3, true).level);
        assertEquals(SelfCheck.Level.OK, SelfCheck.configSync(true, 3, true, 3, true).level);
    }
    @Test public void appNotifyPrefersAppSwitchOverChannels() {
        assertEquals(SelfCheck.Level.FAIL, SelfCheck.appNotify(0, 2).level);
        assertEquals(SelfCheck.Level.WARN, SelfCheck.appNotify(1, 2).level);
        assertEquals(SelfCheck.Level.OK, SelfCheck.appNotify(1, 0).level);
        assertEquals(SelfCheck.Level.UNKNOWN, SelfCheck.appNotify(SelfCheck.UNKNOWN, 0).level);
    }
    @Test public void appBucketOnlyFlagsRestricted() {
        assertNull(SelfCheck.appBucket(40));
        assertNull(SelfCheck.appBucket(SelfCheck.UNKNOWN));
        assertEquals(SelfCheck.Level.WARN, SelfCheck.appBucket(45).level);
    }
}
