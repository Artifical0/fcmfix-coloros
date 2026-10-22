package com.kooritea.fcmfix.ui

import com.kooritea.fcmfix.ui.data.InstalledApp
import com.kooritea.fcmfix.ui.data.ModuleConfig
import com.kooritea.fcmfix.ui.data.ModuleConnection
import com.kooritea.fcmfix.ui.model.AppCheck
import com.kooritea.fcmfix.ui.model.Page
import com.kooritea.fcmfix.ui.model.ReportEnv
import com.kooritea.fcmfix.ui.model.RowKind
import com.kooritea.fcmfix.ui.model.StatusReport
import com.kooritea.fcmfix.ui.model.StatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {
    private fun report(status: StatusSnapshot) = StatusReport.build(
        ReportEnv(null, 36, "", "13.2", null, 0, { it }, { "刚刚" }), status,
    )

    @Test
    fun appItemsMergeConfigAndStatus() {
        val apps = listOf(
            InstalledApp("chat", "Chat", true),
            InstalledApp("mail", "Mail", true),
            InstalledApp("tool", "Tool", false),
        )
        val status = StatusSnapshot(
            answered = true, apps = listOf("chat", "mail"),
            appChecks = mapOf(
                "chat" to AppCheck(false, 0, 0, 10, false, false, 0, 0),
                "mail" to AppCheck(true, 1, 0, 10, false, false, 0, 0),
            ),
        )
        val state = AppUiState(
            ModuleConnection.State.Missing, ModuleConfig(allowList = setOf("chat"), autoAllowFcm = true), apps, report(status),
        )
        val items = appItems(state).associateBy { it.app.packageName }
        assertTrue(items.getValue("chat").attention)
        assertTrue(items.getValue("mail").auto)
        assertEquals(listOf("chat"), appItems(state).filter { AppFilter.ATTENTION.matches(it) }.map { it.app.packageName })
        assertEquals(listOf("chat", "mail"), appItems(state).filter { AppFilter.ALLOWED.matches(it) }.map { it.app.packageName })
        assertTrue(items.getValue("tool").matches("TOO"))
        assertTrue(items.getValue("tool").matches("tool"))
    }

    @Test
    fun timelineSplitsIntroDaysAndNotes() {
        val rows = report(
            StatusSnapshot(
                answered = true,
                events = listOf("…（更早的 2 条已删除）", "10-08 08:00:00 亮屏", "10-09 09:00:00 网络 Wi‑Fi"),
                pushEvents = emptyList(),
            ),
        ).rows(Page.EVENTS)
        val timeline = timeline(rows)
        assertEquals(1, timeline.intro.size)
        assertEquals(listOf("10 月 9 日", "10 月 8 日"), timeline.days.map { it.label })
        assertEquals(listOf("网络 Wi‑Fi"), timeline.days[0].events.map { it.detail })
        assertEquals(1, timeline.notes.size)
    }

    @Test
    fun overviewBlocksGroupChecksUnderTheirSection() {
        val blocks = blocks(report(StatusSnapshot()).rows(Page.OVERVIEW))
        assertEquals(listOf("模块", "Google 服务"), blocks.filterIsInstance<Block.Header>().map { it.title })
        assertEquals(listOf(3, 1), blocks.filterIsInstance<Block.Checks>().map { it.rows.size })
        assertTrue(blocks.filterIsInstance<Block.Checks>().flatMap { it.rows }.all { it.kind == RowKind.CHECK })
    }
}
