package com.kooritea.fcmfix.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kooritea.fcmfix.R
import com.kooritea.fcmfix.ui.model.Page
import com.kooritea.fcmfix.ui.model.Row
import com.kooritea.fcmfix.ui.model.RowKind
import com.kooritea.fcmfix.ui.theme.eventColor
import com.kooritea.fcmfix.util.SelfCheck.EventKind

enum class EventFilter(val label: String, val kinds: Set<EventKind>) {
    FCM("FCM 连接", setOf(EventKind.FCM_UP, EventKind.FCM_RECONNECT, EventKind.FCM_DOWN)),
    NETWORK("网络", setOf(EventKind.NETWORK)),
    DEVICE("亮灭屏与 Doze", setOf(EventKind.SCREEN, EventKind.DOZE)),
    PUSH("推送", setOf(EventKind.PUSH)),
}

data class TimelineDay(val label: String, val events: List<Row>)

/** The EVENTS rows as notes above, one group per day, and the ring-overflow notes below. */
data class Timeline(val intro: List<String>, val days: List<TimelineDay>, val notes: List<String>)

fun timeline(rows: List<Row>): Timeline {
    val intro = ArrayList<String>()
    val days = ArrayList<TimelineDay>()
    val notes = ArrayList<String>()
    var label: String? = null
    var events = ArrayList<Row>()
    var seenTitle = false
    for (row in rows) {
        when (row.kind) {
            RowKind.SECTION -> {
                // The first section is the page title, "最近事件"; the others are days.
                if (!seenTitle) {
                    seenTitle = true
                    continue
                }
                label?.let { days += TimelineDay(it, events) }
                label = row.title
                events = ArrayList()
            }
            RowKind.NOTE -> if (label == null) intro += row.detail else notes += row.detail
            RowKind.EVENT -> events += row
            RowKind.CHECK -> Unit
        }
    }
    label?.let { days += TimelineDay(it, events) }
    return Timeline(intro, days, notes)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineTab(state: AppUiState, actions: AppActions, nav: Navigation, modifier: Modifier = Modifier) {
    var hidden by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val rows = state.report?.rows(Page.EVENTS).orEmpty()
    val timeline = remember(rows) { timeline(rows) }
    TabScaffold(
        title = { Text("时间线") },
        modifier = modifier,
        scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
        actions = {
            IconButton(onClick = actions::refreshStatus) { Icon(painterResource(R.drawable.ic_refresh), contentDescription = "刷新") }
            IconButton(onClick = nav.openSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "设置") }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing && !state.loading,
            onRefresh = actions::refreshStatus,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (filter in EventFilter.entries) {
                            val shown = filter.name !in hidden
                            FilterChip(
                                selected = shown,
                                onClick = { hidden = if (shown) hidden + filter.name else hidden - filter.name },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                }
                when {
                    state.loading -> item {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    rows.isEmpty() -> item {
                        NoteText("系统框架没有响应，看不到时间线。请先在“状态”页处理模块问题：确认已勾选作用域，并在勾选后重启过手机")
                    }
                    else -> {
                        timeline.intro.forEach { item { NoteText(it) } }
                        val kinds = EventFilter.entries.filter { it.name in hidden }.flatMap { it.kinds }.toSet()
                        for (day in timeline.days) {
                            val events = day.events.filter { it.eventKind !in kinds }
                            if (events.isEmpty()) continue
                            stickyHeader { DayHeader(day.label) }
                            itemsIndexed(events) { index, row -> EventItem(row, index, events.size) }
                        }
                        timeline.notes.forEach { item { NoteText(it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Text(
        label,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EventItem(row: Row, index: Int, count: Int) {
    val color = eventColor(row.eventKind)
    val textColor = when (row.eventKind) {
        EventKind.FCM_UP, EventKind.FCM_RECONNECT, EventKind.FCM_DOWN -> color
        EventKind.PUSH -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.groupItem(index, count, MaterialTheme.colorScheme.surfaceBright)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            row.title,
            Modifier.widthIn(min = 64.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(row.detail, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = textColor)
    }
}
