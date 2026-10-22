package com.kooritea.fcmfix.ui.model

import com.kooritea.fcmfix.util.SelfCheck
import com.kooritea.fcmfix.util.SelfCheck.EventKind
import com.kooritea.fcmfix.util.SelfCheck.Level
import com.kooritea.fcmfix.util.SelfCheck.Verdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The self-check is shown across three tabs; the copied text has all of them. */
enum class Page { OVERVIEW, APPS, EVENTS }

enum class RowKind { SECTION, CHECK, EVENT, NOTE }

sealed interface RowAction {
    data class RequestScope(val scopes: List<String>) : RowAction
    data class AppDetails(val packageName: String) : RowAction
    data object FcmDiagnostics : RowAction
}

data class Row(
    val kind: RowKind,
    val page: Page,
    /** INFO for sections, events and notes. */
    val level: Level,
    /** Section name, check name, or an event's time of day. */
    val title: String,
    val detail: String = "",
    val hint: String? = null,
    val action: RowAction? = null,
    /** Counted by the status summary as "needs attention". */
    val problem: Boolean = false,
    /** Extra lines only for the copied text. */
    val copyExtra: String? = null,
    /** App rows: shown with the app's icon. */
    val packageName: String? = null,
    val eventKind: EventKind? = null,
)

/** LSPosed as the module app sees it. */
data class ModuleInfo(
    /** "LSPosed 1.10.2", or "LSPosed" when the framework could not be asked. */
    val framework: String,
    /** The libxposed API the module is built against. */
    val apiVersion: Int,
    /** Scopes FCMFix needs that are not ticked; null when the scope cannot be read. */
    val missingScopes: List<String>?,
)

/** The module app's own copy of the config, to compare with what system_server loaded. */
data class LocalConfig(val manualCount: Int, val autoAllow: Boolean)

/** What the report needs besides the status answer. */
class ReportEnv(
    /** Null while LSPosed has not bound the service. */
    val module: ModuleInfo?,
    val sdkInt: Int,
    val display: String,
    val versionName: String,
    /** Null when the config cannot be read (service not bound). */
    val localConfig: LocalConfig?,
    val now: Long,
    val label: (String) -> String,
    /** DateUtils.getRelativeTimeSpanString on a device, e.g. "5 分钟前". */
    val relativeTime: (Long) -> String,
)

/** The top of the status tab: the first problem, or the FCM connection when all is well. */
data class Summary(val level: Level, val title: String, val detail: String?, val footer: String?)

/** Notification and standby-bucket verdicts of an allowed app; level is the worse of the two. */
class AppVerdict private constructor(val level: Level, val notify: Verdict, val bucket: Verdict?) {
    /** What the app row shows besides pushes, or null when all is well. */
    val text: String?
        get() = if (level == Level.OK) null else notify.text + (bucket?.let { " · " + it.text } ?: "")

    val hint: String? get() = notify.hint ?: bucket?.hint

    /** Counted under "需要注意". */
    val attention: Boolean get() = level == Level.FAIL || level == Level.WARN

    companion object {
        fun of(app: AppCheck): AppVerdict {
            val notify = SelfCheck.appNotify(app.notify, app.blockedChannels, app.auto)
            val bucket = SelfCheck.appBucket(app.bucket)
            var level = notify.level
            if (bucket != null && rank(bucket.level) < rank(level)) level = bucket.level
            return AppVerdict(level, notify, bucket)
        }

        internal fun rank(level: Level): Int = when (level) {
            Level.FAIL -> 0
            Level.WARN -> 1
            Level.UNKNOWN -> 2
            else -> 3
        }
    }
}

/**
 * Turns the self-check answer of the hooked processes into checklist rows. The status tab, the
 * app list and the copied text all use it, so they count the same problems.
 */
class StatusReport private constructor(
    private val env: ReportEnv,
    val status: StatusSnapshot,
    val rows: List<Row>,
) {
    val problems: List<Row> get() = rows.filter { it.problem }

    fun rows(page: Page): List<Row> = rows.filter { it.page == page }

    fun summary(): Summary {
        val problems = problems
        if (problems.isNotEmpty()) {
            val level = if (problems.any { it.level == Level.FAIL }) Level.FAIL else Level.WARN
            val title = if (problems.size == 1) "有 1 项需要处理" else "有 ${problems.size} 项需要处理"
            val first = problems[0]
            return Summary(level, title, first.title + "：" + first.detail.substringBefore('\n'), null)
        }
        if (!status.answered) return Summary(Level.UNKNOWN, "系统框架没有响应", null, null)
        val fcm = status.fcmVerdict(env.now).text.split("\n")
        val detail = if (fcm.size >= 3) "FCM " + fcm[0] + " · " + fcm[1] + "\n" + fcm[2] else null
        return Summary(Level.OK, "一切正常", detail, okFooter())
    }

    /** e.g. "LSPosed 1.10.2 · API 101 · 系统框架 14 项 · 电池 4 项". */
    private fun okFooter(): String? {
        val module = env.module ?: return null
        val parts = mutableListOf(module.framework + " · API " + module.apiVersion)
        for ((process, label) in StatusSnapshot.PROCESSES) {
            status.hooks(process).active?.let { parts += "$label ${it.size} 项" }
        }
        return parts.joinToString(" · ")
    }

    fun toText(time: Date = Date()): String {
        val text = StringBuilder("FCMFix 自查 ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(time))
        for (row in rows) {
            when (row.kind) {
                RowKind.SECTION -> text.append("\n\n【").append(row.title).append("】")
                RowKind.EVENT -> text.append('\n').append(row.title).append(' ').append(row.detail)
                RowKind.NOTE -> text.append("\n• ").append(row.detail)
                RowKind.CHECK -> {
                    text.append('\n').append(markOf(row.level)).append(' ').append(row.title).append("：")
                        .append(row.detail.replace("\n", "；"))
                    row.hint?.let { text.append("\n  → ").append(it) }
                    row.copyExtra?.let { text.append("\n  ").append(it) }
                }
            }
        }
        return text.toString()
    }

    companion object {
        fun build(env: ReportEnv, status: StatusSnapshot): StatusReport =
            StatusReport(env, status, Builder(env, status).build())

        fun markOf(level: Level): String = when (level) {
            Level.OK -> "✓"
            Level.WARN -> "!"
            Level.FAIL -> "✗"
            Level.UNKNOWN -> "?"
            else -> "•"
        }

        /** "10-09" → "10 月 9 日". */
        fun dayLabel(monthDay: String): String = try {
            "${monthDay.substring(0, 2).toInt()} 月 ${monthDay.substring(3, 5).toInt()} 日"
        } catch (e: RuntimeException) {
            monthDay
        }
    }

    private class Builder(private val env: ReportEnv, private val status: StatusSnapshot) {
        private val rows = ArrayList<Row>()
        private var page = Page.OVERVIEW

        fun build(): List<Row> {
            section("模块")
            val module = env.module
            if (module == null) {
                add(Level.FAIL, "LSPosed", "模块未激活", "在 LSPosed 中启用 FCMFix，勾选系统框架和电池后重启手机")
            } else {
                add(Level.OK, "LSPosed", "已激活 · " + module.framework, null)
                addScopeRow(module)
            }
            if (env.sdkInt < 34) {
                add(Level.UNKNOWN, "系统检查", "需要 Android 14 及以上", null)
            } else {
                for ((process, label) in StatusSnapshot.PROCESSES) addHookRow(process, label)
            }
            if (status.answered) addConfigRows()

            section("Google 服务")
            if (status.answered) {
                add("FCM 连接（系统框架检测）", status.fcmVerdict(env.now))
                add("GMS 联网策略", SelfCheck.gmsPolicy(status.gmsPolicy))
                add("GMS 待机分组", SelfCheck.gmsBucket(status.gmsBucket))
                add("GMS 电池优化白名单", SelfCheck.gmsDoze(status.gmsDoze))
            }
            add(
                Level.INFO, "FCM Diagnostics", "点击打开 GMS 自带的诊断页，看是否为 connected。" +
                    "亮屏时也一直 disconnected 通常是网络问题（DNS 被污染或 5228 端口被封），模块无法解决",
                null, RowAction.FcmDiagnostics, problem = false,
            )

            if (!status.answered) return rows
            section("系统信息（反馈问题时附上）")
            val info = StringBuilder()
                .append("FCMFix ").append(env.versionName)
                .append("\nsdk=").append(env.sdkInt)
                .append("\ndisplay=").append(env.display)
            status.settings?.forEach { info.append('\n').append(it) }
            status.ignoreGmsUserSet?.let { info.append("\nIgnoreGmsUserSet=").append(it) }
            add(Level.INFO, "版本与系统设置", info.toString(), null)

            page = Page.APPS
            addAppRows()
            page = Page.EVENTS
            addEventRows()
            return rows
        }

        private fun section(title: String) {
            rows += Row(RowKind.SECTION, page, Level.INFO, title)
        }

        private fun note(text: String) {
            rows += Row(RowKind.NOTE, page, Level.INFO, "", text)
        }

        private fun add(
            level: Level, title: String, detail: String, hint: String?, action: RowAction? = null,
            problem: Boolean = level == Level.FAIL || level == Level.WARN, copyExtra: String? = null,
        ) {
            rows += Row(RowKind.CHECK, page, level, title, detail, hint, action, problem, copyExtra)
        }

        private fun add(title: String, verdict: Verdict) = add(verdict.level, title, verdict.text, verdict.hint)

        private fun addScopeRow(module: ModuleInfo) {
            val missing = module.missingScopes
            when {
                missing == null -> add(Level.UNKNOWN, "作用域", "无法读取", null)
                missing.isEmpty() -> add(Level.OK, "作用域", "系统框架、电池", null)
                else -> {
                    val labels = missing.map { if (it == "system") "系统框架" else "电池" }
                    add(
                        Level.FAIL, "作用域", "缺少" + labels.joinToString("、"),
                        "点击向 LSPosed 申请添加，确认后重启手机", RowAction.RequestScope(missing), problem = true,
                    )
                }
            }
        }

        private fun addHookRow(process: String, label: String) {
            val (active, failed, skipped) = status.hooks(process)
            val skippedSummary = if (skipped.isNullOrEmpty()) "" else "，${skipped.size} 项不适用"
            var copyExtra: String? = null
            if (!active.isNullOrEmpty()) copyExtra = "生效：" + active.joinToString("、")
            if (!skipped.isNullOrEmpty()) {
                copyExtra = (if (copyExtra == null) "" else copyExtra + "\n") + "不适用：" + skipped.joinToString("；")
            }
            val title = "$label Hook"
            when {
                active == null || failed == null -> add(
                    Level.FAIL, title, "未加载", "确认已勾选${label}作用域，并在勾选后重启过手机", copyExtra = copyExtra,
                )
                failed.isNotEmpty() -> add(
                    Level.WARN, title,
                    "${active.size} 项生效$skippedSummary，${failed.size} 项失配\n" + failed.joinToString("\n"),
                    "失配的项目与当前固件不匹配，请导出完整报告反馈", copyExtra = copyExtra,
                )
                else -> add(Level.OK, title, "${active.size} 项生效$skippedSummary", null, copyExtra = copyExtra)
            }
        }

        private fun addConfigRows() {
            env.localConfig?.let { local ->
                add(
                    "配置同步", SelfCheck.configSync(
                        status.configLoaded, status.manualCount, status.autoAllow, local.manualCount, local.autoAllow,
                    ),
                )
            }
            if (status.autoAllow) {
                if (status.fcmPackages < 0) {
                    add(Level.WARN, "自动放行", "已开启，系统框架还没有完成应用扫描", "稍后再查一次；一直如此请重启手机")
                } else {
                    add(Level.INFO, "自动放行", "已开启，系统框架识别到 ${status.fcmPackages} 个包含 FCM 的应用", null)
                }
            }
        }

        /** Problems first, each group by the most recent push. */
        private fun addAppRows() {
            val apps = status.apps ?: return
            section("放行的应用（${apps.size}）")
            if (apps.isEmpty()) {
                add(Level.WARN, "还没有放行任何应用", "在“应用”页放行需要推送的应用，或开启“自动放行包含 FCM 的应用”", null)
                return
            }
            val attention = ArrayList<Pair<Row, Long>>()
            val normal = ArrayList<Pair<Row, Long>>()
            for (name in apps) {
                val app = status.appChecks[name] ?: continue
                val verdict = AppVerdict.of(app)
                var label = env.label(name)
                if (app.auto) label += "（自动）"
                val pushes = if (app.lastPush == 0L) "开机以来还没有收到推送"
                else env.relativeTime(app.lastPush) + "收到推送 · 开机以来 " + app.pushCount + " 次"
                val process = when {
                    app.running -> "运行中"
                    app.stopped -> "已停止，推送时由模块拉起"
                    else -> "未运行"
                }
                val detail = (verdict.text?.let { it + "\n" } ?: "") + pushes + " · " + process
                // Only a fully disabled app counts in the summary; a muted channel is often deliberate.
                val row = Row(
                    RowKind.CHECK, page, verdict.level, label, detail, verdict.hint, RowAction.AppDetails(name),
                    problem = verdict.level == Level.FAIL, packageName = name,
                )
                (if (verdict.attention) attention else normal) += row to app.lastPush
            }
            val order = compareBy<Pair<Row, Long>> { AppVerdict.rank(it.first.level) }.thenByDescending { it.second }
            if (attention.isNotEmpty()) {
                section("需要注意（${attention.size}）")
                rows += attention.sortedWith(order).map { it.first }
            }
            if (normal.isNotEmpty()) {
                section("正常（${normal.size}）")
                rows += normal.sortedWith(order).map { it.first }
            }
            note("“收到推送”表示 GMS 已把消息交给该应用。收到了推送却没有弹出通知，问题通常在应用自己的通知设置或应用本身")
        }

        /** One row per event, newest first, under a section for each day. */
        private fun addEventRows() {
            val events = status.events ?: return
            section("最近事件（开机以来，最新在上）")
            val timeline = SelfCheck.mergeEvents(events, status.pushEvents)
            if (timeline.lines.isEmpty()) {
                note("熄屏、深度 Doze、网络切换、FCM 连接变化和收到推送会记录在这里")
                return
            }
            note("FCM 断线前后是否刚熄屏、进入深度 Doze 或切换网络，通常能说明原因。“重连”是旧连接换成了新连接，中间不影响推送")
            var day: String? = null
            for (line in timeline.lines) {
                // "MM-dd HH:mm:ss text"
                val stamped = line.length > 15 && line[5] == ' ' && line[14] == ' '
                val lineDay = if (stamped) line.substring(0, 5) else ""
                if (lineDay != day) {
                    day = lineDay
                    section(if (stamped) dayLabel(lineDay) else "其他")
                }
                val text = if (stamped) line.substring(15) else line
                rows += Row(
                    RowKind.EVENT, page, Level.INFO, if (stamped) line.substring(6, 14) else "", text,
                    eventKind = SelfCheck.eventKind(text),
                )
            }
            timeline.notes.forEach { note(it) }
        }
    }
}
