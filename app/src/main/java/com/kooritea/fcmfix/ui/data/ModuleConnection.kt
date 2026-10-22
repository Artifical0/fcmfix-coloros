package com.kooritea.fcmfix.ui.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kooritea.fcmfix.ui.model.ModuleInfo
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** LSPosed's service for the module app, bound once per process and shared by every screen. */
object ModuleConnection {
    sealed interface State {
        /** LSPosed hands the service over shortly after the process starts. */
        data object Connecting : State

        data class Bound(val service: XposedService) : State

        /** Not bound within BIND_TIMEOUT_MS, or the service died: the module is not active. */
        data object Missing : State
    }

    private const val TAG = "ModuleConnection"
    private const val BIND_TIMEOUT_MS = 3000L
    private val mutableState = MutableStateFlow<State>(State.Connecting)
    private var started = false

    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Call on the main thread; later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    mutableState.value = State.Bound(service)
                }

                override fun onServiceDied(service: XposedService) {
                    if ((mutableState.value as? State.Bound)?.service === service) mutableState.value = State.Missing
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "registerListener", e)
        }
        // Report "inactive" only if the bind never arrives.
        Handler(Looper.getMainLooper()).postDelayed(
            { mutableState.compareAndSet(State.Connecting, State.Missing) }, BIND_TIMEOUT_MS,
        )
    }

    /** Binder calls: run off the main thread. */
    fun moduleInfo(service: XposedService): ModuleInfo {
        val framework = try {
            service.frameworkName + " " + service.frameworkVersion
        } catch (e: Throwable) {
            Log.w(TAG, e.toString())
            "LSPosed"
        }
        // The module runs against the API it was built for (targetApiVersion in module.prop),
        // not the newest one the framework supports.
        return ModuleInfo(framework, XposedInterface.LIB_API, missingScopes(service))
    }

    /** The scopes FCMFix needs that are not ticked, or null when the scope cannot be read. */
    private fun missingScopes(service: XposedService): List<String>? = try {
        val scope = service.scope
        buildList {
            if ("system" !in scope && "android" !in scope) add("system")
            if ("com.oplus.battery" !in scope) add("com.oplus.battery")
        }
    } catch (e: Throwable) {
        Log.w(TAG, e.toString())
        null
    }
}
