package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (context == null) return

        LogUtil.i(AppConfig.TAG, "BootReceiver received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit

            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
                if (userManager != null && !userManager.isUserUnlocked) {
                    LogUtil.w(AppConfig.TAG, "BootReceiver: User is locked, skipping auto start")
                    return
                }
            }

            else -> {
                LogUtil.w(AppConfig.TAG, "BootReceiver: Unhandled action: $action")
                return
            }
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // The bootstrap may still be running the integrity check / legacy import. A
                // cold snapshot would read the coded default of PREF_IS_BOOTED and silently
                // skip the user's auto-start, so wait (bounded) for the storage layer first.
                if (!StorageBootstrap.awaitReadyOrNull(BOOT_READY_TIMEOUT_MS)) {
                    LogUtil.w(AppConfig.TAG, "BootReceiver: storage not ready; auto start skipped")
                    return@launch
                }

                if (!Prefs.bool(AppConfig.PREF_IS_BOOTED, false)) {
                    LogUtil.i(AppConfig.TAG, "BootReceiver: Auto-start on boot is disabled")
                    return@launch
                }

                // No profile read here: the daemon resolves the selection and reports a missing
                // one through MSG_STATE_START_FAILURE. Starting the foreground service first
                // also keeps this process alive for the subscription sync below.
                LogUtil.i(AppConfig.TAG, "BootReceiver: Starting V2Ray service")
                withContext(Dispatchers.Main.immediate) {
                    LauncherManager.startService(context)
                }

                SubscriptionUpdater.sync(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "BootReceiver: startup failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val BOOT_READY_TIMEOUT_MS = 8_000L
    }
}
