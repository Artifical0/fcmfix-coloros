package com.kooritea.fcmfix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kooritea.fcmfix.R
import com.kooritea.fcmfix.ui.data.InstalledApp
import com.kooritea.fcmfix.ui.data.ModuleConnection
import com.kooritea.fcmfix.ui.model.AppCheck
import com.kooritea.fcmfix.ui.model.AppVerdict
import com.kooritea.fcmfix.ui.theme.levelColor
import com.kooritea.fcmfix.util.SelfCheck.Level
import java.util.Locale

/** An installed app with its allow state and, once allowed, what system_server reports for it. */
data class AppItem(
    val app: InstalledApp,
    val allowed: Boolean,
    /** Allowed by the auto-allow switch rather than ticked by hand. */
    val auto: Boolean,
    /** Only for allowed apps system_server already reported. */
    val check: AppCheck?,
) {
    val verdict: AppVerdict? = check?.let { AppVerdict.of(it) }
    val attention: Boolean get() = allowed && verdict?.attention == true
}

fun appItems(state: AppUiState): List<AppItem> {
    val apps = state.apps ?: return emptyList()
    val config = state.config
    val checks = state.report?.status?.appChecks.orEmpty()
    return apps.map { app ->
        val allowed = config?.allows(app.packageName, app.includeFcm) == true
        AppItem(
            app, allowed,
            auto = config?.allowsAutomatically(app.packageName, app.includeFcm) == true,
            check = if (allowed) checks[app.packageName] else null,
        )
    }
}

enum class AppFilter(val label: String) {
    ALL("全部"), FCM("包含 FCM"), ALLOWED("已放行"), ATTENTION("需要注意");

    fun matches(item: AppItem): Boolean = when (this) {
        ALL -> true
        FCM -> item.app.includeFcm
        ALLOWED -> item.allowed
        ATTENTION -> item.attention
    }
}

fun AppItem.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase(Locale.ROOT)
    return app.label.lowercase(Locale.ROOT).contains(q) || app.packageName.lowercase(Locale.ROOT).contains(q)
}

/** "5 分钟前收到推送". */
@Composable
fun pushText(lastPush: Long): String = LocalRelativeTime.current(lastPush) + "收到推送"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsTab(state: AppUiState, actions: AppActions, nav: Navigation, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    val items = remember(state.apps, state.config, state.report) { appItems(state) }
    val shown = remember(items, query, filter) { items.filter { filter.matches(it) && it.matches(query) } }
    val editable = state.config != null
    val focus = remember { FocusRequester() }

    BackHandler(searching) {
        searching = false
        query = ""
    }
    TabScaffold(
        title = {
            if (searching) {
                LaunchedEffect(Unit) { focus.requestFocus() }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("搜索应用名称或包名") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            } else {
                Text("应用")
            }
        },
        modifier = modifier,
        scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
        navigationIcon = {
            if (searching) {
                IconButton(onClick = {
                    searching = false
                    query = ""
                }) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "关闭搜索") }
            }
        },
        actions = {
            if (searching) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "清除") }
                }
            } else {
                IconButton(onClick = { searching = true }) { Icon(painterResource(R.drawable.ic_search), contentDescription = "搜索") }
                IconButton(onClick = nav.openSettings) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "设置") }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.scanning && state.apps != null,
            onRefresh = actions::scanApps,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    AutoAllowCard(
                        checked = state.config?.autoAllowFcm == true,
                        enabled = editable,
                        onChange = actions::setAutoAllow,
                    )
                }
                item {
                    FilterRow(filter, items) { filter = it }
                }
                when {
                    state.apps == null -> item {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    shown.isEmpty() -> item {
                        Text(
                            if (filter == AppFilter.ATTENTION && query.isBlank()) "放行的应用都没有问题" else "没有匹配的应用",
                            Modifier.fillMaxWidth().padding(48.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> itemsIndexed(shown, key = { _, item -> item.app.packageName }) { index, item ->
                        Column(Modifier.groupItem(index, shown.size, MaterialTheme.colorScheme.surfaceBright)) {
                            if (index > 0) RowDivider()
                            AppRowItem(
                                item,
                                enabled = editable,
                                onToggle = { actions.setAllowed(item.app, it) },
                                onClick = { nav.openApp(item.app.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoAllowCard(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    GroupCard(Modifier.padding(top = 8.dp)) {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("自动放行包含 FCM 的应用", fontWeight = FontWeight.Medium) },
            supportingContent = { Text("新安装的应用也会自动放行；在列表中关闭的应用会被排除") },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        )
    }
}

@Composable
private fun FilterRow(selected: AppFilter, items: List<AppItem>, onSelect: (AppFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (filter in AppFilter.entries) {
            val count = items.count { filter.matches(it) }
            if (filter == AppFilter.ATTENTION && count == 0) continue
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(if (items.isEmpty()) filter.label else "${filter.label} $count") },
            )
        }
    }
}

@Composable
fun AppRowItem(item: AppItem, enabled: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val verdict = item.verdict
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { AppIcon(item.app.packageName, item.app.label) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.app.includeFcm) {
                    Spacer(Modifier.width(6.dp))
                    Pill("FCM", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        },
        supportingContent = {
            Column {
                Text(item.app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                val problem = verdict?.text
                val lastPush = item.check?.lastPush ?: 0
                when {
                    problem != null -> Text(problem, color = levelColor(verdict.level), style = MaterialTheme.typography.bodySmall)
                    item.allowed && lastPush > 0 -> Text(
                        pushText(lastPush), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = { Switch(checked = item.allowed, onCheckedChange = onToggle, enabled = enabled) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailSheet(item: AppItem, state: AppUiState, actions: AppActions, nav: Navigation, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AppDetailContent(
            item,
            editable = state.config != null,
            onToggle = { actions.setAllowed(item.app, it) },
            onOpenSettings = { nav.openAppSettings(item.app.packageName) },
            onExport = {
                onDismiss()
                actions.export(item.app.packageName)
            },
            exportable = state.connection is ModuleConnection.State.Bound,
        )
    }
}

/** The sheet's content, apart so screenshot tests can render it without a window. */
@Composable
fun AppDetailContent(
    item: AppItem,
    editable: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onExport: () -> Unit,
    exportable: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(item.app.packageName, item.app.label, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(item.app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.app.includeFcm || item.auto) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.app.includeFcm) {
                            Pill("包含 FCM", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        if (item.auto) {
                            Pill("自动放行", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        GroupCard {
            ListItem(
                modifier = Modifier.clickable(enabled = editable) { onToggle(!item.allowed) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("放行", fontWeight = FontWeight.Medium) },
                supportingContent = { Text(allowSummary(item)) },
                trailingContent = { Switch(checked = item.allowed, onCheckedChange = onToggle, enabled = editable) },
            )
        }
        val check = item.check
        val verdict = item.verdict
        if (check != null && verdict != null) {
            SectionHeader("开机以来")
            GroupCard {
                DetailRow(verdict.notify.level, "通知", verdict.notify.text, verdict.notify.hint)
                verdict.bucket?.let {
                    RowDivider()
                    DetailRow(it.level, "待机分组", it.text.removePrefix("待机分组："), it.hint)
                }
                RowDivider()
                DetailRow(
                    if (check.lastPush > 0) Level.OK else Level.INFO, "推送",
                    if (check.lastPush == 0L) "还没有收到推送" else pushText(check.lastPush) + " · 共 ${check.pushCount} 次",
                    null,
                )
                RowDivider()
                DetailRow(
                    Level.INFO, "进程",
                    when {
                        check.running -> "运行中"
                        check.stopped -> "已停止，推送时由模块拉起"
                        else -> "未运行"
                    },
                    null,
                )
            }
            NoteText("“收到推送”表示 GMS 已把消息交给该应用。收到了推送却没有弹出通知，问题通常在应用自己的通知设置或应用本身")
        } else {
            NoteText(
                if (item.allowed) "系统框架还没有报告这个应用，稍后刷新“状态”再看" else "放行后，这里会显示通知状态和推送记录",
                Modifier.padding(top = 8.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("应用设置")
            }
            if (item.allowed && exportable) {
                FilledTonalButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_report), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导出报告")
                }
            }
        }
    }
}

private fun allowSummary(item: AppItem): String = when {
    item.auto -> "由“自动放行包含 FCM 的应用”放行，关闭后会被排除"
    item.allowed -> "收到推送时，模块会唤醒并放行这个应用"
    item.app.includeFcm -> "放行后，收到推送时模块会唤醒这个应用"
    else -> "没有检测到 FCM 组件，放行通常没有作用"
}

@Composable
private fun DetailRow(level: Level, title: String, text: String, hint: String?) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { LevelIcon(level, size = 36.dp) },
        overlineContent = { Text(title) },
        headlineContent = { Text(text) },
        supportingContent = hint?.let { { Text(it, color = levelColor(level), style = MaterialTheme.typography.bodySmall) } },
    )
}
