package com.kooritea.fcmfix.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.kooritea.fcmfix.ui.data.InstalledApp
import com.kooritea.fcmfix.ui.theme.FcmFixTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Every screen composes with real data and the main interactions reach AppActions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h892dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class UiSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private class Recorder : AppActions by AppActions.None {
        val calls = mutableListOf<String>()
        override fun setAllowed(target: InstalledApp, allowed: Boolean) { calls += "allow ${target.packageName} $allowed" }
        override fun setAutoAllow(enabled: Boolean) { calls += "auto $enabled" }
        override fun allowAllFcm() { calls += "allowAll" }
        override fun requestScope(scopes: List<String>) { calls += "scope $scopes" }
        override fun startExport() { calls += "export" }
        override fun refreshStatus() { calls += "refresh" }
    }

    private fun show(state: AppUiState, tab: Tab = Tab.STATUS, settings: Boolean = false, actions: AppActions = AppActions.None) {
        compose.setContent {
            Samples.Provide {
                FcmFixTheme(darkTheme = false, dynamicColor = false) {
                    FcmFixScreen(state, actions, startTab = tab, startInSettings = settings)
                }
            }
        }
    }

    /** Lazy lists only compose what is on screen. */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun healthyStatus() {
        show(Samples.healthy())
        compose.onNodeWithText("一切正常").assertIsDisplayed()
        compose.onNodeWithText("LSPosed 1.10.2 · API 101 · 系统框架 14 项 · 电池 4 项").assertExists()
        scrollTo("FCM 连接（系统框架检测）")
        compose.onNodeWithText("FCM 连接（系统框架检测）").assertIsDisplayed()
    }

    @Test
    fun problemsLeadWithTheFirstOneAndScopeRowAsksLsposed() {
        val actions = Recorder()
        show(Samples.problems(), actions = actions)
        compose.onNodeWithText("有 5 项需要处理").assertIsDisplayed()
        compose.onNodeWithText("作用域").performClick()
        assertEquals(listOf("scope [com.oplus.battery]"), actions.calls)
    }

    @Test
    fun loading() {
        show(Samples.loading())
        compose.onNodeWithText("正在检查…").assertIsDisplayed()
    }

    @Test
    fun inactiveModuleDisablesSwitches() {
        show(Samples.inactive(), tab = Tab.APPS)
        compose.onNode(isToggleable() and hasAnyAncestor(hasText("Telegram", substring = true))).assertIsNotEnabled()
    }

    @Test
    fun appListTogglesAndSearches() {
        val actions = Recorder()
        show(Samples.healthy(), tab = Tab.APPS, actions = actions)
        scrollTo("WhatsApp")
        compose.onNode(isToggleable() and hasAnyAncestor(hasText("WhatsApp", substring = true))).performClick()
        compose.onNodeWithText("自动放行包含 FCM 的应用").performClick()
        assertEquals(listOf("allow com.whatsapp true", "auto true"), actions.calls)
        compose.onNodeWithContentDescription("搜索").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("calc")
        compose.onNodeWithText("计算器").assertExists()
        compose.onAllNodesWithText("Telegram").assertCountEquals(0)
    }

    @Test
    fun attentionFilterAndDetailSheet() {
        show(Samples.problems(), tab = Tab.APPS)
        compose.onNodeWithText("需要注意 2").performClick()
        compose.onAllNodesWithText("Telegram").assertCountEquals(0)
        compose.onNodeWithText("Discord").performClick()
        compose.onNodeWithText("放行").assertExists()
        compose.onNodeWithText("开机以来").assertExists()
        compose.onAllNodesWithText("通知已关闭").onFirst().assertExists()
    }

    @Test
    fun tabsAndTimeline() {
        show(Samples.healthy())
        compose.onAllNodesWithText("时间线").onFirst().performClick()
        compose.onNodeWithText("推送 → org.telegram.messenger").assertExists()
        compose.onNodeWithText("推送").performClick()
        compose.onAllNodesWithText("推送 → org.telegram.messenger").assertCountEquals(0)
        compose.onAllNodesWithText("应用").onFirst().performClick()
        compose.onNodeWithText("Telegram").assertExists()
    }

    @Test
    fun settingsConfirmsAllowAll() {
        val actions = Recorder()
        show(Samples.healthy(), settings = true, actions = actions)
        compose.onNodeWithText("放行所有包含 FCM 的应用").performClick()
        compose.onNodeWithText("放行").performClick()
        assertEquals(listOf("allowAll"), actions.calls)
    }
}
