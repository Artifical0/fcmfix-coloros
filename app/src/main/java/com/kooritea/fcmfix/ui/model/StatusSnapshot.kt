package com.kooritea.fcmfix.ui.model

import android.os.Bundle
import com.kooritea.fcmfix.util.HookStatus
import com.kooritea.fcmfix.util.SelfCheck

/** Hook groups of one process; active and failed are null when the module did not load there. */
data class HookGroups(
    val active: List<String>?,
    val failed: List<String>?,
    val skipped: List<String>?,
)

/** What system_server reports for one allowed app. */
data class AppCheck(
    val auto: Boolean,
    val notify: Int,
    val blockedChannels: Int,
    val bucket: Int,
    val running: Boolean,
    val stopped: Boolean,
    val lastPush: Long,
    val pushCount: Long,
)

/**
 * The status query answer as plain data, so the report and the screens can be built and
 * tested without a Bundle. Defaults match what the Bundle getters return for missing keys.
 */
data class StatusSnapshot(
    /** system_server answered the self-check part of the query. */
    val answered: Boolean = false,
    /** Keyed by process: "android" (system framework) and "com.oplus.battery". */
    val hooks: Map<String, HookGroups> = emptyMap(),
    val configLoaded: Boolean = false,
    val manualCount: Int = 0,
    val autoAllow: Boolean = false,
    val fcmPackages: Int = SelfCheck.UNKNOWN,
    val gmsPolicy: Int = SelfCheck.UNKNOWN,
    val gmsBucket: Int = SelfCheck.UNKNOWN,
    val gmsDoze: Int = SelfCheck.UNKNOWN,
    val settings: List<String>? = null,
    val ignoreGmsUserSet: Boolean? = null,
    val fcmState: Int = SelfCheck.UNKNOWN,
    val fcmRemote: String? = null,
    val fcmStall: String? = null,
    val fcmSince: Long = 0,
    val fcmReconnects: Int = 0,
    val fcmOutages: Int = 0,
    val fcmLongestOutage: Long = 0,
    val fcmLastSeen: Long = 0,
    val fcmMonitorStart: Long = 0,
    /** Packages system_server lets through, in its order; null when it did not answer. */
    val apps: List<String>? = null,
    val appChecks: Map<String, AppCheck> = emptyMap(),
    val events: List<String>? = null,
    val pushEvents: List<String>? = null,
) {
    fun hooks(process: String): HookGroups = hooks[process] ?: HookGroups(null, null, null)

    fun fcmVerdict(now: Long): SelfCheck.Verdict = SelfCheck.fcmConnection(
        fcmState, fcmRemote, fcmStall, fcmSince, fcmReconnects, fcmOutages, fcmLongestOutage,
        fcmLastSeen, fcmMonitorStart, now,
    )

    companion object {
        val PROCESSES = listOf("android" to "系统框架", "com.oplus.battery" to "电池")

        fun from(status: Bundle): StatusSnapshot {
            val apps = status.getStringArrayList(SelfCheck.KEY_APPS)
            val checks = LinkedHashMap<String, AppCheck>()
            apps?.forEach { name ->
                val app = status.getBundle(SelfCheck.APP_PREFIX + name) ?: return@forEach
                checks[name] = AppCheck(
                    auto = app.getBoolean(SelfCheck.APP_AUTO),
                    notify = app.getInt(SelfCheck.APP_NOTIFY, SelfCheck.UNKNOWN),
                    blockedChannels = app.getInt(SelfCheck.APP_BLOCKED_CHANNELS, SelfCheck.UNKNOWN),
                    bucket = app.getInt(SelfCheck.APP_BUCKET, SelfCheck.UNKNOWN),
                    running = app.getBoolean(SelfCheck.APP_RUNNING),
                    stopped = app.getBoolean(SelfCheck.APP_STOPPED),
                    lastPush = app.getLong(SelfCheck.APP_LAST_PUSH),
                    pushCount = app.getLong(SelfCheck.APP_PUSH_COUNT),
                )
            }
            return StatusSnapshot(
                answered = status.containsKey(SelfCheck.KEY_CONFIG_LOADED),
                hooks = PROCESSES.associate { (process, _) ->
                    process to HookGroups(
                        status.getStringArrayList(process + HookStatus.ACTIVE_SUFFIX),
                        status.getStringArrayList(process + HookStatus.FAILED_SUFFIX),
                        status.getStringArrayList(process + HookStatus.SKIPPED_SUFFIX),
                    )
                },
                configLoaded = status.getBoolean(SelfCheck.KEY_CONFIG_LOADED),
                manualCount = status.getInt(SelfCheck.KEY_MANUAL_COUNT),
                autoAllow = status.getBoolean(SelfCheck.KEY_AUTO_ALLOW),
                fcmPackages = status.getInt(SelfCheck.KEY_FCM_PACKAGES, SelfCheck.UNKNOWN),
                gmsPolicy = status.getInt(SelfCheck.KEY_GMS_POLICY, SelfCheck.UNKNOWN),
                gmsBucket = status.getInt(SelfCheck.KEY_GMS_BUCKET, SelfCheck.UNKNOWN),
                gmsDoze = status.getInt(SelfCheck.KEY_GMS_DOZE, SelfCheck.UNKNOWN),
                settings = status.getStringArrayList(SelfCheck.KEY_SETTINGS),
                ignoreGmsUserSet = if (status.containsKey(HookStatus.KEY_IGNORE_GMS_USER_SET)) {
                    status.getBoolean(HookStatus.KEY_IGNORE_GMS_USER_SET)
                } else {
                    null
                },
                fcmState = status.getInt(SelfCheck.KEY_FCM_STATE, SelfCheck.UNKNOWN),
                fcmRemote = status.getString(SelfCheck.KEY_FCM_REMOTE),
                fcmStall = status.getString(SelfCheck.KEY_FCM_STALL),
                fcmSince = status.getLong(SelfCheck.KEY_FCM_SINCE),
                fcmReconnects = status.getInt(SelfCheck.KEY_FCM_RECONNECTS),
                fcmOutages = status.getInt(SelfCheck.KEY_FCM_OUTAGES),
                fcmLongestOutage = status.getLong(SelfCheck.KEY_FCM_LONGEST_OUTAGE),
                fcmLastSeen = status.getLong(SelfCheck.KEY_FCM_LAST_SEEN),
                fcmMonitorStart = status.getLong(SelfCheck.KEY_FCM_MONITOR_START),
                apps = apps,
                appChecks = checks,
                events = status.getStringArrayList(SelfCheck.KEY_EVENTS),
                pushEvents = status.getStringArrayList(SelfCheck.KEY_PUSH_EVENTS),
            )
        }
    }
}
