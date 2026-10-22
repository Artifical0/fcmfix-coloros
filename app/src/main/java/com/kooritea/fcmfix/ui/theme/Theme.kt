package com.kooritea.fcmfix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kooritea.fcmfix.util.SelfCheck.EventKind
import com.kooritea.fcmfix.util.SelfCheck.Level

/** The indigo of the 13.0 views, for systems without wallpaper colours. */
private val IndigoLight = lightColorScheme(
    primary = Color(0xFF4338CA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    inversePrimary = Color(0xFFA5B4FC),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFF0369A1),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF0C3A55),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF172033),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF172033),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceTint = Color(0xFF4338CA),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E8F0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDE3EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F4F9),
    surfaceContainerHigh = Color(0xFFE9EDF4),
    surfaceContainerHighest = Color(0xFFE2E8F0),
)

private val IndigoDark = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    inversePrimary = Color(0xFF4338CA),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF1E293B),
    secondaryContainer = Color(0xFF334155),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFF7DD3FC),
    onTertiary = Color(0xFF0C3A55),
    tertiaryContainer = Color(0xFF0C3A55),
    onTertiaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF2A2F3A),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceTint = Color(0xFFA5B4FC),
    inverseSurface = Color(0xFFE5E7EB),
    inverseOnSurface = Color(0xFF1C1F26),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFF2A2F3A),
    surfaceBright = Color(0xFF2C313B),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF16191F),
    surfaceContainer = Color(0xFF1A1D23),
    surfaceContainerHigh = Color(0xFF23272F),
    surfaceContainerHighest = Color(0xFF2A2F3A),
)

/** Check results keep their meaning under any wallpaper colour, so they are not themed. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val fail: Color, val neutral: Color)

private val StatusLight = StatusColors(Color(0xFF15803D), Color(0xFFB45309), Color(0xFFB91C1C), Color(0xFF64748B))
private val StatusDark = StatusColors(Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFFF87171), Color(0xFF9CA3AF))

val LocalStatusColors = staticCompositionLocalOf { StatusLight }

/** Wallpaper colours on Android 12 and later; dynamic is off in screenshot tests. */
@Composable
fun FcmFixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> IndigoDark
        else -> IndigoLight
    }
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) StatusDark else StatusLight) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

@Composable
fun levelColor(level: Level): Color {
    val colors = LocalStatusColors.current
    return when (level) {
        Level.OK -> colors.ok
        Level.WARN -> colors.warn
        Level.FAIL -> colors.fail
        else -> colors.neutral
    }
}

@Composable
fun eventColor(kind: EventKind?): Color {
    val colors = LocalStatusColors.current
    return when (kind) {
        EventKind.FCM_UP -> colors.ok
        EventKind.FCM_RECONNECT -> colors.warn
        EventKind.FCM_DOWN -> colors.fail
        EventKind.NETWORK -> MaterialTheme.colorScheme.primary
        EventKind.PUSH -> MaterialTheme.colorScheme.tertiary
        else -> colors.neutral
    }
}
