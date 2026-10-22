package com.kooritea.fcmfix.ui.data

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.kooritea.fcmfix.ui.model.StatusSnapshot
import com.kooritea.fcmfix.util.HookStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Runs the bundled collect-report.sh as root; the script only reads state and logs. */
object ReportExporter {
    private const val TAG = "ReportExporter"

    class Failure(message: String) : Exception(message)

    /** Returns the report file in the cache; throws Failure with a message for the user. */
    suspend fun export(context: Context, packageName: String?, appendix: String): File = withContext(Dispatchers.IO) {
        try {
            val script = File(context.cacheDir, "collect-report.sh")
            context.assets.open("collect-report.sh").use { input -> FileOutputStream(script).use { input.copyTo(it) } }
            val dir = File(context.cacheDir, "reports")
            dir.listFiles()?.forEach { if (!it.delete()) Log.w(TAG, "cannot delete $it") }
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
            val report = File(
                dir, "fcmfix-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt",
            )
            val command = "sh " + script.absolutePath + " -" + (packageName?.let { " $it" } ?: "")
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            process.outputStream.close()
            val reader = Thread({
                try {
                    process.inputStream.use { input -> FileOutputStream(report).use { input.copyTo(it) } }
                } catch (e: IOException) {
                    Log.w(TAG, e.toString())
                }
            }, "FCMFix-report-reader")
            reader.start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.join(5000)
            val head = readHead(report)
            if (!head.startsWith("FCMFix report")) {
                throw Failure(if (head.isBlank()) "没有获得 Root 权限" else head.trim())
            }
            FileOutputStream(report, true).use { it.write(appendix.toByteArray()) }
            report
        } catch (e: IOException) {
            throw Failure("无法执行 su：" + e.message)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Failure("已中断")
        }
    }

    /** Report sections for the module log lines each hooked process kept since boot. */
    fun logsText(logs: Bundle): String {
        val text = StringBuilder()
        for ((process, label) in StatusSnapshot.PROCESSES) {
            text.append("\n== FCMFix 模块日志（").append(label).append("，由模块自身保留，不受 logcat 缓冲区限制）\n")
            val lines = logLines(logs, process + HookStatus.LOGS_SUFFIX)
            if (lines == null) text.append("未响应：模块未在该进程加载\n") else lines.forEach { text.append(it).append('\n') }
        }
        return text.toString()
    }

    /** Packed since 13.0-rc7; a process still running an older version sends the plain list. */
    private fun logLines(logs: Bundle, key: String): List<String>? {
        logs.getByteArray(key)?.let { packed ->
            return try {
                HookStatus.unpack(packed)
            } catch (e: IOException) {
                listOf("无法解压日志：$e")
            }
        }
        return logs.getStringArrayList(key)
    }

    private fun readHead(file: File): String {
        if (!file.isFile) return ""
        val buffer = ByteArray(512)
        val read = file.inputStream().use { it.read(buffer) }
        return if (read <= 0) "" else String(buffer, 0, read)
    }
}
