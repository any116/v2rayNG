package com.v2ray.ang.receiver

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.WidgetRunState
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.WidgetStateManager
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.ui.widget.SwitchWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps the historical component name and provider metadata so widgets already placed on the home
 * screen survive the move to Glance.
 *
 * Declared in `:bg` because WorkManager runs there: Glance resolves a running session from process
 * memory, so the receiver and the session worker must share a process.
 */
class WidgetProvider : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = SwitchWidget()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppConfig.BROADCAST_ACTION_WIDGET_CLICK -> handleAsync(context) { toggle(context) }

            AppConfig.BROADCAST_ACTION_ACTIVITY -> {
                val state = WidgetStateManager.fromServiceMessage(intent.getIntExtra("key", 0))
                    ?: return
                handleAsync(context) { WidgetStateManager.publish(state) }
            }

            else -> super.onReceive(context, intent)
        }
    }

    /**
     * A click records a request and forwards it; it never claims the switch already flipped.
     * The daemon is asked first so a stale snapshot cannot send a stop command to a dead core,
     * and a pending command is dropped instead of enqueued twice.
     */
    private suspend fun toggle(context: Context) {
        when (val state = WidgetStateManager.reconcile(context)) {
            WidgetRunState.RUNNING -> {
                WidgetStateManager.publish(WidgetRunState.STOPPING)
                LauncherManager.stopService(context)
            }

            WidgetRunState.STARTING, WidgetRunState.STOPPING -> {
                LogUtil.i(AppConfig.TAG, "Widget: ignoring click, $state already requested")
            }

            else -> start(context)
        }
    }

    private fun start(context: Context) {
        if (requiresVpnPermission(context)) {
            // The system consent dialog needs a visible Activity; a receiver cannot show one.
            WidgetStateManager.publish(WidgetRunState.PERMISSION_REQUIRED)
            context.startActivity(
                AppRoute.Main.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        WidgetStateManager.publish(WidgetRunState.STARTING)
        if (!LauncherManager.startServiceFromToggle(context)) {
            WidgetStateManager.publish(WidgetRunState.STOPPED)
        }
    }

    private fun requiresVpnPermission(context: Context): Boolean {
        if (SettingsManager.isRootMode() || !SettingsManager.isVpnMode()) return false
        return runCatching { VpnService.prepare(context) != null }.getOrDefault(false)
    }

    private fun handleAsync(context: Context, block: suspend () -> Unit) {
        // goAsync() hands out the single PendingResult, so super.onReceive must not run as well.
        val pendingResult = goAsync()
        scope.launch {
            try {
                block()
                glanceAppWidget.updateAll(context)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Widget: failed to handle broadcast", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
