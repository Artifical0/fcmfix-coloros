package com.kooritea.fcmfix.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

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
    @Test public void durationRoundsDownToMinutes() {
        assertEquals("不到 1 分钟", SelfCheck.duration(59_000));
        assertEquals("5 分钟", SelfCheck.duration(5 * 60_000 + 30_000));
        assertEquals("2 小时 3 分钟", SelfCheck.duration(123 * 60_000));
        assertEquals("不到 1 分钟", SelfCheck.duration(-1));
    }
    @Test public void fcmConnectionVerdicts() {
        long start = 1_000_000;
        SelfCheck.Verdict connected = SelfCheck.fcmConnection(SelfCheck.FCM_CONNECTED, "1.2.3.4:5228",
                start + 600_000, 3, 1, 4 * 60_000, start + 600_000 + 30 * 60_000, start,
                start + 600_000 + 30 * 60_000);
        assertEquals(SelfCheck.Level.OK, connected.level);
        assertTrue(connected.text.contains("已持续 30 分钟"));
        assertTrue(connected.text.contains("重连 3 次，断线 1 次（最长约 4 分钟）"));
        SelfCheck.Verdict sinceBoot = SelfCheck.fcmConnection(SelfCheck.FCM_CONNECTED, "1.2.3.4:5228",
                start + 1000, 0, 0, 0, start + 3_600_000, start, start + 3_600_000);
        assertTrue(sinceBoot.text.contains("开始监测时已连接"));
        assertTrue(sinceBoot.text.contains("没有断线或重连"));
        SelfCheck.Verdict down = SelfCheck.fcmConnection(SelfCheck.FCM_NONE, null, 0, 0, 1, 0,
                start, start, start + 5 * 60_000);
        assertEquals(SelfCheck.Level.WARN, down.level);
        assertTrue(down.text.contains("已断线约 5 分钟"));
        assertEquals(SelfCheck.Level.WARN, SelfCheck.fcmConnection(SelfCheck.FCM_CONNECTING, "x", 0, 0, 0, 0, 0, start, start).level);
        assertEquals(SelfCheck.Level.UNKNOWN, SelfCheck.fcmConnection(SelfCheck.UNKNOWN, null, 0, 0, 0, 0, 0, start, start).level);
    }
    @Test public void reconnectsAloneAreReassuring() {
        assertEquals("开机以来重连 12 次，每次都马上连上了新连接，不影响推送", SelfCheck.fcmHistory(12, 0, 0));
        assertEquals("开机以来断线 2 次（最长约 3 分钟）", SelfCheck.fcmHistory(0, 2, 3 * 60_000));
    }
    @Test public void eventKindsFollowMonitorPrefixes() {
        assertEquals(SelfCheck.EventKind.PUSH, SelfCheck.eventKind(SelfCheck.EVENT_PUSH + "org.telegram.messenger"));
        assertEquals(SelfCheck.EventKind.FCM_UP, SelfCheck.eventKind(SelfCheck.EVENT_FCM_RESTORED + " 1.2.3.4:5228"));
        assertEquals(SelfCheck.EventKind.FCM_RECONNECT, SelfCheck.eventKind(SelfCheck.EVENT_FCM_RECONNECT + " x"));
        assertEquals(SelfCheck.EventKind.FCM_DOWN, SelfCheck.eventKind(SelfCheck.EVENT_FCM_DOWN + "（x）"));
        assertEquals(SelfCheck.EventKind.NETWORK, SelfCheck.eventKind("网络断开"));
        assertEquals(SelfCheck.EventKind.DOZE, SelfCheck.eventKind(SelfCheck.EVENT_DOZE_EXIT));
        assertEquals(SelfCheck.EventKind.SCREEN, SelfCheck.eventKind(SelfCheck.EVENT_SCREEN_OFF));
    }
    @Test public void mergeEventsInterleavesNewestFirst() {
        SelfCheck.Timeline timeline = SelfCheck.mergeEvents(
                Arrays.asList("…（更早的 96 条已丢弃）", "10-10 09:10:36 亮屏", "10-10 09:10:49 FCM 断线"),
                Arrays.asList("10-10 09:07:08 推送 → a", "10-10 09:11:26 推送 → b"));
        assertEquals(Arrays.asList("10-10 09:11:26 推送 → b", "10-10 09:10:49 FCM 断线",
                "10-10 09:10:36 亮屏", "10-10 09:07:08 推送 → a"), timeline.lines);
        assertEquals(Collections.singletonList("连接、网络、亮灭屏和 Doze 事件：更早的 96 条已丢弃"), timeline.notes);
        assertTrue(SelfCheck.mergeEvents(null, null).lines.isEmpty());
    }
}
