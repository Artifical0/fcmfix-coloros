package com.kooritea.fcmfix.ui.model

import com.kooritea.fcmfix.util.SelfCheck
import com.kooritea.fcmfix.util.SelfCheck.EventKind
import com.kooritea.fcmfix.util.SelfCheck.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class StatusReportTest {
    private val now = 1_760_000_000_000L
    private val module = ModuleInfo("LSPosed 1.10.2", 101, emptyList())

    private fun env(module: ModuleInfo? = this.module, local: LocalConfig? = LocalConfig(2, false), sdk: Int = 36) = ReportEnv(
        module = module, sdkInt = sdk, display = "PLK110_17.0.0.105", versionName = "13.2",
        localConfig = local, now = now,
        label = { "Label($it)" },
        relativeTime = { "${(now - it) / 60_000} 分钟前" },
    )

    private val hooks = mapOf(
        "android" to HookGroups(listOf("BroadcastFix", "AutoStartFix"), emptyList(), emptyList()),
        "com.oplus.battery" to HookGroups(listOf("ControllerCenter"), emptyList(), null),
    )

    private val healthy = StatusSnapshot(
        answered = true, hooks = hooks, configLoaded = true, manualCount = 2,
        gmsPolicy = 0, gmsBucket = 10, gmsDoze = 1,
        fcmState = SelfCheck.FCM_CONNECTED, fcmRemote = "1.2.3.4:5228（Wi‑Fi）",
        fcmSince = now - 3 * 3_600_000L, fcmMonitorStart = now - 5 * 3_600_000L,
        apps = listOf("chat"),
        appChecks = mapOf("chat" to AppCheck(false, 1, 0, 10, true, false, now - 60_000L, 1)),
        events = emptyList(),
    )

    @Test
    fun inactiveModuleCountsLsposedAndBothHookGroups() {
        val report = StatusReport.build(env(module = null, local = null), StatusSnapshot())
        val summary = report.summary()
        assertEquals(Level.FAIL, summary.level)
        assertEquals("有 3 项需要处理", summary.title)
        assertEquals("LSPosed：模块未激活", summary.detail)
        assertEquals(
            listOf("模块", "LSPosed", "系统框架 Hook", "电池 Hook", "Google 服务", "FCM Diagnostics"),
            report.rows.map { it.title },
        )
        assertEquals(RowAction.FcmDiagnostics, report.rows.last().action)
        assertFalse(report.rows.last().problem)
    }

    @Test
    fun healthySummaryShowsFcmAndFramework() {
        val summary = StatusReport.build(env(), healthy).summary()
        assertEquals(Level.OK, summary.level)
        assertEquals("一切正常", summary.title)
        assertEquals("FCM 已连接 1.2.3.4:5228（Wi‑Fi） · 已持续 3 小时 0 分钟\n开机以来没有断线或重连", summary.detail)
        assertEquals("LSPosed 1.10.2 · API 101 · 系统框架 2 项 · 电池 1 项", summary.footer)
    }

    @Test
    fun missingScopeRowAsksLsposed() {
        val report = StatusReport.build(env(module = module.copy(missingScopes = listOf("com.oplus.battery"))), healthy)
        val scope = report.rows.single { it.title == "作用域" }
        assertEquals(Level.FAIL, scope.level)
        assertEquals("缺少电池", scope.detail)
        assertEquals(RowAction.RequestScope(listOf("com.oplus.battery")), scope.action)
        assertTrue(scope.problem)
    }

    @Test
    fun hookRowListsMismatchesAndKeepsSkippedForTheCopy() {
        val status = healthy.copy(
            hooks = hooks + ("android" to HookGroups(listOf("A"), listOf("B: no method"), listOf("C: absent"))),
        )
        val row = StatusReport.build(env(), status).rows.single { it.title == "系统框架 Hook" }
        assertEquals(Level.WARN, row.level)
        assertEquals("1 项生效，1 项不适用，1 项失配\nB: no method", row.detail)
        assertEquals("失配的项目与当前固件不匹配，请导出完整报告反馈", row.hint)
        assertEquals("生效：A\n不适用：C: absent", row.copyExtra)
    }

    @Test
    fun configSyncComparesTheConfigOfTheQuery() {
        val mismatch = StatusReport.build(env(local = LocalConfig(3, false)), healthy).rows.single { it.title == "配置同步" }
        assertEquals(Level.WARN, mismatch.level)
        val noLocal = StatusReport.build(env(local = null), healthy).rows
        assertTrue(noLocal.none { it.title == "配置同步" })
    }

    @Test
    fun appRowsPutProblemsFirstThenByLatestPush() {
        val status = healthy.copy(
            apps = listOf("ok.old", "muted", "off", "ok.new", "auto.off"),
            appChecks = mapOf(
                "ok.old" to AppCheck(false, 1, 0, 10, true, false, now - 30 * 60_000L, 3),
                "muted" to AppCheck(false, 1, 2, 10, false, false, now - 60_000L, 1),
                "off" to AppCheck(false, 0, 0, 10, false, true, 0, 0),
                "ok.new" to AppCheck(false, 1, 0, 45, false, false, now - 5 * 60_000L, 7),
                "auto.off" to AppCheck(true, 0, 0, 10, false, false, 0, 0),
            ),
        )
        val rows = StatusReport.build(env(), status).rows(Page.APPS)
        assertEquals(
            listOf(
                "放行的应用（5）", "需要注意（3）", "Label(off)", "Label(muted)", "Label(ok.new)",
                "正常（2）", "Label(ok.old)", "Label(auto.off)（自动）", "",
            ),
            rows.map { it.title },
        )
        val off = rows.single { it.packageName == "off" }
        assertEquals("通知已关闭\n开机以来还没有收到推送 · 已停止，推送时由模块拉起", off.detail)
        assertTrue(off.problem)
        assertEquals(RowAction.AppDetails("off"), off.action)
        val muted = rows.single { it.packageName == "muted" }
        assertFalse("A muted channel is often deliberate", muted.problem)
        assertEquals("2 个通知类别已关闭\n1 分钟前收到推送 · 开机以来 1 次 · 未运行", muted.detail)
        assertEquals("待机分组：受限", rows.single { it.packageName == "ok.new" }.detail.substringAfter(" · ").substringBefore('\n'))
        assertEquals(Level.INFO, rows.single { it.packageName == "auto.off" }.level)
        assertEquals(RowKind.NOTE, rows.last().kind)
    }

    @Test
    fun noAllowedAppIsAWarning() {
        val report = StatusReport.build(env(), healthy.copy(apps = emptyList(), appChecks = emptyMap()))
        val rows = report.rows(Page.APPS)
        assertEquals(listOf("放行的应用（0）", "还没有放行任何应用"), rows.map { it.title })
        assertEquals("有 1 项需要处理", report.summary().title)
        assertEquals(Level.WARN, rows[1].level)
    }

    @Test
    fun eventsAreGroupedByDayNewestFirst() {
        val status = healthy.copy(
            events = listOf("…（更早的 3 条已删除）", "10-08 23:59:01 熄屏", "10-09 00:01:02 FCM 断线"),
            pushEvents = listOf("10-09 00:00:30 推送 → com.example"),
        )
        val rows = StatusReport.build(env(), status).rows(Page.EVENTS)
        assertEquals(
            listOf("最近事件（开机以来，最新在上）", "", "10 月 9 日", "00:01:02", "00:00:30", "10 月 8 日", "23:59:01", ""),
            rows.map { it.title },
        )
        assertEquals(listOf(EventKind.FCM_DOWN, EventKind.PUSH, EventKind.SCREEN), rows.mapNotNull { it.eventKind })
        assertEquals("连接、网络、亮灭屏和 Doze 事件：更早的 3 条已删除", rows.last().detail)
    }

    @Test
    fun copiedTextKeepsTheIssueFormat() {
        val status = StatusSnapshot(hooks = hooks)
        val text = StatusReport.build(env(local = null), status).toText(Date(0))
        val expected = "FCMFix 自查 " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(Date(0)) +
            "\n\n【模块】" +
            "\n✓ LSPosed：已激活 · LSPosed 1.10.2" +
            "\n✓ 作用域：系统框架、电池" +
            "\n✓ 系统框架 Hook：2 项生效\n  生效：BroadcastFix、AutoStartFix" +
            "\n✓ 电池 Hook：1 项生效\n  生效：ControllerCenter" +
            "\n\n【Google 服务】" +
            "\n• FCM Diagnostics：点击打开 GMS 自带的诊断页，看是否为 connected。亮屏时也一直 disconnected 通常是网络问题（DNS 被污染或 5228 端口被封），模块无法解决"
        assertEquals(expected, text)
    }

    @Test
    fun androidThirteenSkipsTheHookQuery() {
        val rows = StatusReport.build(env(sdk = 33), StatusSnapshot()).rows
        val check = rows.single { it.title == "系统检查" }
        assertEquals(Level.UNKNOWN, check.level)
        assertNull(rows.firstOrNull { it.title.endsWith(" Hook") })
    }

    @Test
    fun dayLabelFallsBackToTheRawText() {
        assertEquals("10 月 9 日", StatusReport.dayLabel("10-09"))
        assertEquals("ab-cd", StatusReport.dayLabel("ab-cd"))
    }
}
