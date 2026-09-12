package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.WidgetRunState
import com.v2ray.ang.helper.MessageHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Switch state for the home screen widget.
 */
object WidgetStateManager {

    /** A command that is never answered must not block the switch forever. */
    private const val PENDING_TIMEOUT_MS = 20_000L
    private const val QUERY_TIMEOUT_MS = 2_000L

    private val flow by lazy { MutableStateFlow(restore()) }

    val state: StateFlow<WidgetRunState> get() = flow.asStateFlow()

    fun publish(state: WidgetRunState) {
        MmkvManager.encodeSettings(AppConfig.CACHE_WIDGET_STATE, state.name)
        MmkvManager.encodeSettings(AppConfig.CACHE_WIDGET_STATE_AT, System.currentTimeMillis())
        flow.value = state
    }

    /** Translates a service broadcast, or null when it says nothing about the switch. */
    fun fromServiceMessage(key: Int): WidgetRunState? = when (key) {
        AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> WidgetRunState.RUNNING
        AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> WidgetRunState.STOPPED
        AppConfig.MSG_STATE_START_FAILURE -> WidgetRunState.FAILED
        else -> null
    }

    /**
     * Asks the daemon for the real state instead of trusting a snapshot another process wrote.
     *
     * A fresh pending command is kept as is, so a click is not reported as stopped while the core
     * is still starting. An unanswered query keeps the last known state rather than inventing one.
     */
    suspend fun reconcile(context: Context): WidgetRunState {
        if (isPendingFresh()) return flow.value

        val known = flow.value
        val running = withTimeoutOrNull(QUERY_TIMEOUT_MS) { queryDaemon(context) }
        val resolved = when {
            running == null -> known
            running -> WidgetRunState.RUNNING
            known == WidgetRunState.FAILED -> WidgetRunState.FAILED
            else -> WidgetRunState.STOPPED
        }
        publish(resolved)
        return resolved
    }

    /**
     * The ordered result is the only cross-process answer available without loading the core:
     * the daemon acknowledges the registration, an absent daemon leaves the initial canceled code.
     */
    private suspend fun queryDaemon(context: Context): Boolean =
        suspendCancellableCoroutine { continuation ->
            MessageHelper.sendMsg2ServiceForResult(
                context,
                AppConfig.MSG_REGISTER_CLIENT,
                ""
            ) { handled ->
                if (continuation.isActive) continuation.resume(handled)
            }
        }

    private fun isPendingFresh(): Boolean {
        if (!flow.value.isPending) return false
        val age = System.currentTimeMillis() - stateAt()
        return age in 0..PENDING_TIMEOUT_MS
    }

    private fun restore(): WidgetRunState {
        val stored = WidgetRunState.from(MmkvManager.decodeSettingsString(AppConfig.CACHE_WIDGET_STATE))
        if (!stored.isPending) return stored
        val age = System.currentTimeMillis() - stateAt()
        return if (age in 0..PENDING_TIMEOUT_MS) stored else WidgetRunState.UNKNOWN
    }

    private fun stateAt(): Long =
        MmkvManager.decodeSettingsLong(AppConfig.CACHE_WIDGET_STATE_AT, 0L)
}
