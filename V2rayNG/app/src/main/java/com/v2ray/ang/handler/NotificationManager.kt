package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.math.min

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_ICON_THRESHOLD = 3000
    private const val QUERY_INTERVAL_MS = 3000L

    // Includes posting/removal, not just state mutations: an in-flight sample must never repost
    // after stopForeground, or overwrite a newer screen-off/configuration refresh.
    private val notificationLock = Any()
    private var notificationContent: CoreNotificationContent? = null
    private var speedNotificationJob: Job? = null

    /** Starts at most one traffic sampler; notification recovery does not depend on this setting. */
    fun startSpeedNotification(): Unit = synchronized(notificationLock) {
        val service = getService() ?: return@synchronized
        if (notificationContent == null || Prefs.bool(AppConfig.PREF_SPEED_ENABLED) != true) return@synchronized
        if (speedNotificationJob?.isActive == true || !CoreServiceManager.isRunning()) return@synchronized
        // A network reload can call this while the device is asleep, without another SCREEN_OFF.
        if (service.getSystemService(PowerManager::class.java)?.isInteractive == false) return@synchronized

        val scope = CoroutineScope(PlatformDependencies.ioDispatcher(service))
        speedNotificationJob = scope.launch(start = CoroutineStart.LAZY) {
            val samplingJob = coroutineContext.job
            var lastQueryTime = SystemClock.elapsedRealtime()
            var lastZeroSpeed = false
            try {
                while (isActive) {
                    delay(QUERY_INTERVAL_MS)
                    coroutineContext.ensureActive()
                    val queryTime = SystemClock.elapsedRealtime()
                    val elapsed = queryTime - lastQueryTime
                    if (elapsed < QUERY_INTERVAL_MS) continue
                    lastZeroSpeed = updateSpeedNotificationOnce(samplingJob, lastZeroSpeed, elapsed / 1000.0)
                    lastQueryTime = queryTime
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to update core traffic notification", e)
            } finally {
                synchronized(notificationLock) {
                    if (speedNotificationJob === samplingJob) speedNotificationJob = null
                }
            }
        }
        // Publish the identity before dispatching: an old sampler's finally cannot clear this job.
        speedNotificationJob?.start()
    }

    /** Shows a fresh foreground notification when a profile is started or reloaded. */
    fun showNotification(currentConfig: ProfileItem?): Unit = synchronized(notificationLock) {
        val service = getService() ?: return@synchronized
        val content = CoreNotificationContent(title = currentConfig?.remarks)
        notificationContent = content
        service.startForeground(NOTIFICATION_ID, buildNotification(service, content))
    }

    /**
     * Rebuilds only an existing notification, even with traffic disabled or the UI process absent.
     * Used for language/configuration changes and screen-on/unlock; never resurrects a stopped service.
     */
    fun refreshNotification(localeCode: String? = null): Unit = synchronized(notificationLock) {
        val content = notificationContent ?: return@synchronized
        val service = getService() ?: return@synchronized
        postNotification(service, content, localeCode)
    }

    /**
     * Fulfills the foreground-service contract before a start command can return early.
     * Even the cold-start placeholder contains both actions, without waiting for storage or traffic.
     */
    fun ensureForeground(): Unit = synchronized(notificationLock) {
        val service = getService() ?: return@synchronized
        val content = notificationContent ?: CoreNotificationContent().also { notificationContent = it }
        service.startForeground(NOTIFICATION_ID, buildNotification(service, content))
    }

    /** Cancels the sampler and forgets the notification even if the service reference has gone away. */
    fun cancelNotification(): Unit = synchronized(notificationLock) {
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        notificationContent = null
        getService()?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    /** Pauses traffic while asleep, retaining the normal notification and both service actions. */
    fun stopSpeedNotification(): Unit = synchronized(notificationLock) {
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        val content = notificationContent?.copy(text = null, icon = R.drawable.ic_stat_name) ?: return@synchronized
        notificationContent = content
        val service = getService() ?: return@synchronized
        postNotification(service, content)
    }

    private fun buildNotification(
        service: Service,
        content: CoreNotificationContent,
        localeCode: String? = null
    ): Notification {
        val resourceContext = localeCode
            ?.takeIf(String::isNotBlank)
            ?.let { AppLocaleManager.localizedContext(service, it) }
            ?: AppLocaleManager.localizedContext(service)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val contentPendingIntent = PendingIntent.getActivity(
            service,
            NOTIFICATION_PENDING_INTENT_CONTENT,
            Intent(service, MainActivity::class.java),
            flags
        )
        val stopPendingIntent = PendingIntent.getBroadcast(
            service,
            NOTIFICATION_PENDING_INTENT_STOP_V2RAY,
            Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                `package` = AppConfig.ANG_PACKAGE
                putExtra("key", AppConfig.MSG_STATE_STOP)
            },
            flags
        )
        val restartPendingIntent = PendingIntent.getBroadcast(
            service,
            NOTIFICATION_PENDING_INTENT_RESTART_V2RAY,
            Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                `package` = AppConfig.ANG_PACKAGE
                putExtra("key", AppConfig.MSG_STATE_RESTART)
            },
            flags
        )
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(resourceContext)
        } else {
            ""
        }
        return buildCoreNotification(
            resourceContext, channelId, content, contentPendingIntent, stopPendingIntent, restartPendingIntent
        )
    }

    // All callers hold notificationLock through the actual post, so removal cannot be overtaken.
    private fun postNotification(service: Service, content: CoreNotificationContent, localeCode: String? = null) {
        service.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(service, content, localeCode))
    }

    /** Creates a silent foreground-service channel without overriding user-owned importance. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(context: Context): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = context.resources.getString(R.string.notification_channel_core_background_service)
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
            ?: return channelId
        val channel = notificationManager.getNotificationChannel(channelId)
        if (channel == null) {
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
                lightColor = Color.DKGRAY
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }.also(notificationManager::createNotificationChannel)
        } else if (channel.name?.toString() != channelName) {
            channel.name = channelName
            notificationManager.createNotificationChannel(channel)
        }
        return channelId
    }

    /** Appends one outbound's uplink/downlink rates. */
    private fun appendSpeedString(text: StringBuilder, name: String?, up: Double, down: Double) {
        var n = name ?: "no tag"
        n = n.take(min(n.length, 6))
        text.append(n)
        for (i in n.length..6 step 2) {
            text.append("\t")
        }
        text.append("•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n")
    }

    /** Queries outside the notification lock, then publishes only if this sampler is still current. */
    private fun updateSpeedNotificationOnce(
        samplingJob: Job,
        lastZeroSpeed: Boolean,
        sinceLastQueryInSeconds: Double
    ): Boolean {
        var proxyUplink = 0L
        var proxyDownlink = 0L
        var directUplink = 0L
        var directDownlink = 0L

        CoreServiceManager.queryAllOutboundTrafficStats().forEach { stat ->
            when {
                stat.tag == AppConfig.TAG_DIRECT -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> directUplink += stat.value
                        AppConfig.DOWNLINK -> directDownlink += stat.value
                    }
                }

                // Include custom subscription outbound tags in proxy traffic.
                stat.tag != AppConfig.TAG_BLOCKED -> {
                    when (stat.direction) {
                        AppConfig.UPLINK -> proxyUplink += stat.value
                        AppConfig.DOWNLINK -> proxyDownlink += stat.value
                    }
                }
            }
        }

        val proxyTotal = proxyUplink + proxyDownlink
        val directTotal = directUplink + directDownlink
        val zeroSpeed = proxyTotal + directTotal == 0L
        if (!zeroSpeed || !lastZeroSpeed) {
            val text = StringBuilder()
            appendSpeedString(
                text, AppConfig.TAG_PROXY,
                proxyUplink / sinceLastQueryInSeconds,
                proxyDownlink / sinceLastQueryInSeconds
            )
            appendSpeedString(
                text, AppConfig.TAG_DIRECT,
                directUplink / sinceLastQueryInSeconds,
                directDownlink / sinceLastQueryInSeconds
            )
            val icon = when {
                proxyTotal < NOTIFICATION_ICON_THRESHOLD && directTotal < NOTIFICATION_ICON_THRESHOLD ->
                    R.drawable.ic_stat_name
                proxyTotal > directTotal -> R.drawable.ic_stat_proxy
                else -> R.drawable.ic_stat_direct
            }
            synchronized(notificationLock) {
                if (!samplingJob.isActive || speedNotificationJob !== samplingJob) return zeroSpeed
                val content = notificationContent?.copy(text = text.toString(), icon = icon) ?: return zeroSpeed
                val service = getService() ?: return zeroSpeed
                notificationContent = content
                postNotification(service, content)
            }
        }
        return zeroSpeed
    }

    private fun getService(): Service? = CoreServiceManager.serviceControl?.get()?.getService()
}
