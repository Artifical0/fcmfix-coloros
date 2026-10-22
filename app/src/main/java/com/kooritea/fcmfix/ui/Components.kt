package com.kooritea.fcmfix.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kooritea.fcmfix.R
import com.kooritea.fcmfix.ui.data.IconSource
import com.kooritea.fcmfix.ui.model.Row
import com.kooritea.fcmfix.ui.theme.levelColor
import com.kooritea.fcmfix.util.SelfCheck.Level

val LocalIconSource = compositionLocalOf<IconSource> { IconSource.None }

/** "5 分钟前"; screenshot tests replace it to get the same text on every run. */
val LocalRelativeTime = staticCompositionLocalOf<(Long) -> String> {
    { time -> DateUtils.getRelativeTimeSpanString(time).toString() }
}

val CardShape = RoundedCornerShape(20.dp)

fun levelIcon(level: Level): Int = when (level) {
    Level.OK -> R.drawable.ic_level_ok
    Level.WARN -> R.drawable.ic_level_warn
    Level.FAIL -> R.drawable.ic_level_fail
    Level.UNKNOWN -> R.drawable.ic_level_unknown
    Level.INFO -> R.drawable.ic_level_info
}

/** The check result as an icon in a tinted circle. */
@Composable
fun LevelIcon(level: Level, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = levelColor(level)
    Box(
        modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(levelIcon(level)), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Grouped rows, as in the system settings. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
    ) {
        Column(content = content)
    }
}

@Composable
fun RowDivider(startIndent: Dp = 72.dp) {
    HorizontalDivider(Modifier.padding(start = startIndent), color = MaterialTheme.colorScheme.surfaceContainer)
}

@Composable
fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A check: result icon, name, details, and what to do about it in the result's colour. */
@Composable
fun CheckRowItem(row: Row, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { LevelIcon(row.level) },
        headlineContent = { Text(row.title, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                if (row.detail.isNotEmpty()) Text(row.detail)
                row.hint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = levelColor(row.level), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = if (row.action != null) {
            { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) }
        } else {
            null
        },
    )
}

/** The app's icon, or its first letter while the icon loads or when there is none. */
@Composable
fun AppIcon(packageName: String, label: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val source = LocalIconSource.current
    val icon by produceState(source.cached(packageName), packageName, source) {
        if (value == null) value = source.load(packageName)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
    )
}

/**
 * One row of a card that a lazy list draws item by item: the first item rounds the top
 * corners, the last the bottom ones.
 */
fun Modifier.groupItem(index: Int, count: Int, color: Color): Modifier {
    val top = if (index == 0) 20.dp else 0.dp
    val bottom = if (index == count - 1) 20.dp else 0.dp
    return padding(horizontal = 16.dp)
        .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
        .background(color)
}
