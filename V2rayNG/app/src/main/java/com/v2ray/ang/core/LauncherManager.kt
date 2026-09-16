package com.v2ray.ang.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.service.CoreProxyOnlyService
import com.v2ray.ang.service.CoreRootService
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.util.LogUtil

object LauncherManager {

    /** Guid requested for adoption at startup; persisted by the service, not by the receiver. */
    const val EXTRA_SELECTED_GUID = "launcher_selected_guid"

    fun startServiceFromToggle(context: Context): Boolean =
        startContextService(context, null)

    fun startService(context: Context, guid: String? = null) {
        LogUtil.i(AppConfig.TAG, "LauncherManager: startService from ${context::class.java.simpleName}")
        startContextService(context, guid)
    }

    fun stopService(context: Context) {
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /** Restarts the active daemon without starting a stopped service. */
    fun restartService(context: Context) {
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
    }

    /** Restarts the active daemon, or delegates to the caller's permission-aware start flow. */
    fun restartServiceOrStart(context: Context, startIfStopped: () -> Unit) {
        MessageHelper.sendMsg2ServiceForResult(context, AppConfig.MSG_STATE_RESTART, "") { handled ->
            if (!handled) startIfStopped()
        }
    }

    /**
     * Validates only what lives in the preference snapshot, then starts the service.
     *
     * Profile-dependent validation (a selection exists, the profile parses, the address is
     * usable, the allow-insecure warning) moved into the service-side startup sequence: those
     * checks need the database, and a receiver must not touch it. Failures now reach the user as
     * MSG_STATE_START_FAILURE, and the insecure warning travels as MSG_WARN_INSECURE.
     *
     * @return true when the start request was handed to the system.
     */
    private fun startContextService(context: Context, guid: String?): Boolean {
        if (Prefs.bool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: root mode requires root but none available")
            context.toast(R.string.toast_root_required)
            return false
        }

        val intent = if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }.apply {
            guid?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_SELECTED_GUID, it) }
        }

        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Missing permission to start foreground service", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            false
        } catch (e: RuntimeException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                context.toast(e.message ?: e.javaClass.simpleName)
            }
            false
        }
    }
}
