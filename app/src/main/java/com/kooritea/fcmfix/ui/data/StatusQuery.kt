package com.kooritea.fcmfix.ui.data

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.kooritea.fcmfix.util.HookStatus
import com.kooritea.fcmfix.util.SelfCheck
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object StatusQuery {
    /** An ordered broadcast normally comes back within a second; a stuck receiver must not hang the UI. */
    private const val TIMEOUT_MS = 20_000L

    /**
     * Asks system_server and Battery for their hook status plus what extra selects:
     * SelfCheck.EXTRA_CHECK or HookStatus.EXTRA_LOGS. The answer is empty when no hooked
     * process answers, or on Android 13 and older.
     */
    suspend fun query(context: Context, extra: String = SelfCheck.EXTRA_CHECK): Bundle {
        if (Build.VERSION.SDK_INT < 34) return Bundle()
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val query = Intent(context.packageName + HookStatus.QUERY_ACTION_SUFFIX).putExtra(extra, true)
                val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val extras = getResultExtras(false)
                        if (continuation.isActive) continuation.resume(extras ?: Bundle())
                    }
                }
                context.sendOrderedBroadcast(query, null, options, receiver, Handler(Looper.getMainLooper()), 0, null, null)
            }
        } ?: Bundle()
    }
}
