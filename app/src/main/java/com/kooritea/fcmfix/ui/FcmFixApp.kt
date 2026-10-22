package com.kooritea.fcmfix.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kooritea.fcmfix.R
import com.kooritea.fcmfix.ui.data.InstalledApp
import com.kooritea.fcmfix.ui.data.ModuleConfig
import com.kooritea.fcmfix.ui.data.ModuleConnection
import com.kooritea.fcmfix.ui.model.RowAction
import com.kooritea.fcmfix.ui.model.StatusReport
import kotlinx.coroutines.launch
import java.io.File

enum class Tab { STATUS, APPS, TIMELINE }

/** Everything the screens show: from MainViewModel, or built by hand in screenshot tests. */
data class AppUiState(
    val connection: ModuleConnection.State,
    val config: ModuleConfig?,
    val apps: List<InstalledApp>?,
    val report: StatusReport?,
    val refreshing: Boolean = false,
    val scanning: Boolean = false,
    val versionName: String = "",
) {
    /** Nothing to show until LSPosed answered (or never will) and the first query returned. */
    val loading: Boolean get() = report == null || connection == ModuleConnection.State.Connecting
}

/** What the screens can change; MainViewModel implements it. */
interface AppActions {
    fun refreshStatus()
    fun scanApps()
    fun setAllowed(target: InstalledApp, allowed: Boolean)
    fun setAutoAllow(enabled: Boolean)
    fun setKeepNotifications(enabled: Boolean)
    fun setIceBox(enabled: Boolean)
    fun allowAllFcm()
    fun requestScope(scopes: List<String>)
    fun copyText(): String?
    fun startExport()
    fun export(packageName: String?)
    fun message(text: String)

    object None : AppActions {
        override fun refreshStatus() = Unit
        override fun scanApps() = Unit
        override fun setAllowed(target: InstalledApp, allowed: Boolean) = Unit
        override fun setAutoAllow(enabled: Boolean) = Unit
        override fun setKeepNotifications(enabled: Boolean) = Unit
        override fun setIceBox(enabled: Boolean) = Unit
        override fun allowAllFcm() = Unit
        override fun requestScope(scopes: List<String>) = Unit
        override fun copyText(): String? = null
        override fun startExport() = Unit
        override fun export(packageName: String?) = Unit
        override fun message(text: String) = Unit
    }
}

@Composable
fun FcmFixApp(viewModel: MainViewModel) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val configError by viewModel.configError.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val appListUnreadable by viewModel.appListUnreadable.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Re-check on return: pushes may have arrived, or notifications were turned on in settings.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshStatus()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    val state = AppUiState(connection, config, apps, report, refreshing, scanning, viewModel.versionName)
    CompositionLocalProvider(LocalIconSource provides viewModel.icons) {
        FcmFixScreen(state, viewModel, snackbar)
    }

    configError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConfigError,
            title = { Text("更新配置文件失败") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = viewModel::dismissConfigError) { Text("确定") } },
        )
    }
    if (appListUnreadable) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAppListUnreadable,
            title = { Text("无法读取应用列表") },
            text = { Text("请在系统设置中允许 FCMFix 读取应用列表，然后重新打开。") },
            confirmButton = { TextButton(onClick = viewModel::dismissAppListUnreadable) { Text("确定") } },
        )
    }
    ExportDialogs(export, viewModel)
}

@Composable
private fun ExportDialogs(export: ExportState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        viewModel.saveReport(it)
    }
    when (export) {
        ExportState.Idle -> Unit
        is ExportState.ChooseApp -> AlertDialog(
            onDismissRequest = viewModel::dismissExport,
            title = { Text("选择收不到通知的应用") },
            text = {
                LazyColumn {
                    item { ChoiceRow("不指定应用") { viewModel.export(null) } }
                    items(export.apps) { (packageName, label) -> ChoiceRow(label) { viewModel.export(packageName) } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissExport) { Text("取消") } },
        )
        ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("正在生成报告，需要授予 Root 权限…")
                }
            },
            confirmButton = {},
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissExport,
            title = { Text("导出失败") },
            text = { Text(export.message + "\n\n导出完整报告需要 Root。没有 Root 时，可以先用“复制结果”反馈自查内容。") },
            confirmButton = { TextButton(onClick = viewModel::dismissExport) { Text("确定") } },
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = viewModel::dismissExport,
            title = { Text("报告已生成") },
            text = {
                Text("报告大小 ${maxOf(1, export.file.length() / 1024)} KB。报告包含已安装应用的包名，公开发布前可以自行打码。")
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        viewModel.dismissExport()
                        save.launch(export.file.name)
                    }) { Text("保存到文件") }
                    TextButton(onClick = {
                        viewModel.dismissExport()
                        shareReport(context, export.file)
                    }) { Text("分享") }
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissExport) { Text("关闭") } },
        )
    }
}

@Composable
private fun ChoiceRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun shareReport(context: Context, report: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".reports", report)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "分享报告"))
}

/** The three tabs and the settings page; the start arguments let screenshot tests open any of them. */
@Composable
fun FcmFixScreen(
    state: AppUiState,
    actions: AppActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    startTab: Tab = Tab.STATUS,
    startInSettings: Boolean = false,
) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var settings by rememberSaveable { mutableStateOf(startInSettings) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = remember(actions, context) {
        Navigation(
            context = context,
            actions = actions,
            openSettings = { settings = true },
            openApp = { detail = it },
            copy = {
                val text = actions.copyText()
                if (text != null) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("FCMFix 自查", text))
                    scope.launch { snackbar.showSnackbar("已复制") }
                }
            },
        )
    }

    if (settings) {
        BackHandler { settings = false }
        SettingsScreen(state, actions, nav, snackbar, onBack = { settings = false })
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // Each tab's top bar takes the status bar; the navigation bar takes the bottom.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            val attention = remember(state) { appItems(state).count { it.attention } }
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.STATUS,
                    onClick = { tab = Tab.STATUS },
                    icon = {
                        Icon(
                            painterResource(if (tab == Tab.STATUS) R.drawable.ic_nav_status_filled else R.drawable.ic_nav_status),
                            contentDescription = null,
                        )
                    },
                    label = { Text("状态") },
                )
                NavigationBarItem(
                    selected = tab == Tab.APPS,
                    onClick = { tab = Tab.APPS },
                    icon = {
                        BadgedBox(badge = { if (attention > 0) Badge { Text("$attention") } }) {
                            Icon(painterResource(R.drawable.ic_nav_apps), contentDescription = null)
                        }
                    },
                    label = { Text("应用") },
                )
                NavigationBarItem(
                    selected = tab == Tab.TIMELINE,
                    onClick = { tab = Tab.TIMELINE },
                    icon = { Icon(painterResource(R.drawable.ic_nav_timeline), contentDescription = null) },
                    label = { Text("时间线") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.STATUS -> StatusTab(state, actions, nav, modifier)
            Tab.APPS -> AppsTab(state, actions, nav, modifier)
            Tab.TIMELINE -> TimelineTab(state, actions, nav, modifier)
        }
    }

    detail?.let { packageName ->
        // Gone after a rescan if the app was uninstalled meanwhile.
        val item = remember(state, packageName) { appItems(state).firstOrNull { it.app.packageName == packageName } }
        if (item != null) AppDetailSheet(item, state, actions, nav, onDismiss = { detail = null })
    }
}

/** Things that leave the app or open another page. */
class Navigation(
    private val context: Context,
    private val actions: AppActions,
    val openSettings: () -> Unit,
    val openApp: (String) -> Unit,
    val copy: () -> Unit,
) {
    fun run(action: RowAction) {
        when (action) {
            is RowAction.RequestScope -> actions.requestScope(action.scopes)
            is RowAction.AppDetails -> openApp(action.packageName)
            RowAction.FcmDiagnostics -> start(
                Intent(Intent.ACTION_VIEW).setComponent(
                    ComponentName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics"),
                ),
                "无法打开 FCM Diagnostics",
            )
        }
    }

    fun openAppSettings(packageName: String) = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        "无法打开应用设置",
    )

    fun openUrl(url: String) = start(Intent(Intent.ACTION_VIEW, url.toUri()), "无法打开链接")

    private fun start(intent: Intent, failure: String) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            actions.message(failure)
        } catch (e: SecurityException) {
            actions.message(failure)
        }
    }
}

/** A tab's own top bar; the outer scaffold only adds the navigation bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScaffold(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        content = content,
    )
}
