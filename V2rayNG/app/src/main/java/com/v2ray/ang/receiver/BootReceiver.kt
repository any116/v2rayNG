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
import kotlinx.coroutines.withTimeoutOrNull

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
                val completed = withTimeoutOrNull(BOOT_RECEIVER_TIMEOUT_MS) {
                    // Queue this before waiting for storage. The durable Worker owns the
                    // readiness wait and can retry even if this receiver reaches its callback
                    // deadline.
                    if (!SubscriptionUpdater.enqueueSync(context, requireAutoStart = true)) {
                        LogUtil.e(AppConfig.TAG, "BootReceiver: failed to enqueue subscription schedule sync")
                    }

                    // The bootstrap may still be running the integrity check. A cold snapshot
                    // would read the coded default of PREF_IS_BOOTED and silently skip the
                    // user's auto-start, so wait for a bounded period before reading it.
                    StorageBootstrap.retry()
                    if (!StorageBootstrap.awaitReadyOrNull(BOOT_READY_TIMEOUT_MS)) {
                        LogUtil.w(AppConfig.TAG, "BootReceiver: storage not ready; auto start skipped")
                        return@withTimeoutOrNull false
                    }

                    if (!Prefs.bool(AppConfig.PREF_IS_BOOTED, false)) {
                        LogUtil.i(AppConfig.TAG, "BootReceiver: Auto-start on boot is disabled")
                        return@withTimeoutOrNull true
                    }

                    // No profile read here: the daemon resolves the selection and reports a
                    // missing one through MSG_STATE_START_FAILURE. Subscription scheduling is
                    // handed to a durable Worker instead of running inside goAsync().
                    LogUtil.i(AppConfig.TAG, "BootReceiver: Starting V2Ray service")
                    withContext(Dispatchers.Main.immediate) {
                        LauncherManager.startService(context)
                    }
                    true
                }
                if (completed == null) {
                    LogUtil.w(AppConfig.TAG, "BootReceiver: startup handoff timed out")
                }
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
        /** Keep the receiver callback bounded; longer scheduling work runs in WorkManager. */
        const val BOOT_RECEIVER_TIMEOUT_MS = 8_000L
        const val BOOT_READY_TIMEOUT_MS = 7_000L
    }
}
