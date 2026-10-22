package com.kooritea.fcmfix.ui.screenshots

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import com.kooritea.fcmfix.ui.AppDetailContent
import com.kooritea.fcmfix.ui.AppUiState
import com.kooritea.fcmfix.ui.AppActions
import com.kooritea.fcmfix.ui.FcmFixScreen
import com.kooritea.fcmfix.ui.Samples
import com.kooritea.fcmfix.ui.Tab
import com.kooritea.fcmfix.ui.appItems
import com.kooritea.fcmfix.ui.theme.FcmFixTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen to app/build/outputs/roborazzi for review. Needs Robolectric's native
 * graphics (x86_64 or macOS), so it only runs with -Pscreenshots; see app/build.gradle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h892dp-xxhdpi")
class ScreenshotTest {
    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        captureRoboImage("build/outputs/roborazzi/$name${if (dark) "_dark" else ""}.png") {
            Samples.Provide {
                FcmFixTheme(darkTheme = dark, dynamicColor = false, content = content)
            }
        }
    }

    private fun screens(dark: Boolean) {
        capture("1_status_ok", dark) { FcmFixScreen(Samples.healthy(), AppActions.None) }
        capture("2_status_problems", dark) { FcmFixScreen(Samples.problems(), AppActions.None) }
        capture("3_apps", dark) { FcmFixScreen(Samples.problems(), AppActions.None, startTab = Tab.APPS) }
        capture("4_app_detail", dark) { Detail(Samples.problems(), "com.discord") }
        capture("5_timeline", dark) { FcmFixScreen(Samples.healthy(), AppActions.None, startTab = Tab.TIMELINE) }
        capture("6_settings", dark) { FcmFixScreen(Samples.healthy(), AppActions.None, startInSettings = true) }
    }

    @Test
    fun light() = screens(dark = false)

    @Test
    fun dark() = screens(dark = true)

    @Test
    fun otherStates() {
        capture("7_loading") { FcmFixScreen(Samples.loading(), AppActions.None) }
        capture("8_inactive") { FcmFixScreen(Samples.inactive(), AppActions.None) }
        capture("9_app_detail_not_allowed") { Detail(Samples.healthy(), "com.whatsapp") }
    }

    @Test
    fun largeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        capture("10_status_font130") { FcmFixScreen(Samples.problems(), AppActions.None) }
        capture("11_apps_font130") { FcmFixScreen(Samples.problems(), AppActions.None, startTab = Tab.APPS) }
    }

    @Composable
    private fun Detail(state: AppUiState, packageName: String) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
            AppDetailContent(
                appItems(state).first { it.app.packageName == packageName },
                editable = true, onToggle = {}, onOpenSettings = {}, onExport = {}, exportable = true,
            )
        }
    }
}
