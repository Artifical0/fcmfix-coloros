package com.kooritea.fcmfix.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kooritea.fcmfix.R
import com.kooritea.fcmfix.ui.model.Page
import com.kooritea.fcmfix.ui.model.Row
import com.kooritea.fcmfix.ui.model.RowKind
import com.kooritea.fcmfix.ui.model.Summary
import com.kooritea.fcmfix.ui.theme.levelColor
import com.kooritea.fcmfix.util.SelfCheck.Level

/** A page's rows as cards: each section's checks share one card; notes stand alone. */
sealed interface Block {
    data class Header(val title: String) : Block
    data class Checks(val rows: List<Row>) : Block
    data class Note(val text: String) : Block
}

fun blocks(rows: List<Row>): List<Block> {
    val blocks = ArrayList<Block>()
    var checks = ArrayList<Row>()
    fun flush() {
        if (checks.isNotEmpty()) blocks += Block.Checks(checks)
        checks = ArrayList()
    }
    for (row in rows) {
        when (row.kind) {
            RowKind.SECTION -> {
                flush()
                blocks += Block.Header(row.title)
            }
            RowKind.NOTE -> {
                flush()
                blocks += Block.Note(row.detail)
            }
            RowKind.CHECK, RowKind.EVENT -> checks += row
        }
    }
    flush()
    return blocks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusTab(state: AppUiState, actions: AppActions, nav: Navigation, modifier: Modifier = Modifier) {
    val report = state.report
    TabScaffold(
        title = { Text("FCMFix") },
        modifier = modifier,
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
                item { SummaryCard(if (state.loading) null else report?.summary()) }
                if (report == null || state.loading) return@LazyColumn
                for (block in blocks(report.rows(Page.OVERVIEW))) {
                    when (block) {
                        is Block.Header -> item { SectionHeader(block.title) }
                        is Block.Note -> item { NoteText(block.text) }
                        is Block.Checks -> item {
                            GroupCard {
                                block.rows.forEachIndexed { index, row ->
                                    if (index > 0) RowDivider()
                                    val action = row.action
                                    CheckRowItem(
                                        row,
                                        if (action != null) Modifier.clickable { nav.run(action) } else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
                item { SectionHeader("反馈问题") }
                item { FeedbackCard(onCopy = nav.copy, onExport = actions::startExport) }
            }
        }
    }
}

/** The verdict at the top: the first problem, or the FCM connection when all is well. */
@Composable
fun SummaryCard(summary: Summary?, modifier: Modifier = Modifier) {
    val level = summary?.level ?: Level.UNKNOWN
    val tint = levelColor(level).copy(alpha = if (summary == null) 0f else 0.12f)
    Card(
        modifier.fillMaxWidth().padding(16.dp),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = tint.compositeOver(MaterialTheme.colorScheme.surfaceBright)),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
            if (summary == null) {
                CircularProgressIndicator(Modifier.size(48.dp).padding(6.dp), strokeWidth = 3.dp)
            } else {
                LevelIcon(level, size = 48.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    summary?.title ?: "正在检查…",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                summary?.detail?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                summary?.footer?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FeedbackCard(onCopy: () -> Unit, onExport: () -> Unit) {
    GroupCard {
        Text(
            "反馈问题时请附上自查结果。有 Root 时可以导出完整报告，其中还有系统日志。",
            Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("复制结果")
            }
            FilledTonalButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_report), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("导出完整报告")
            }
        }
    }
}
