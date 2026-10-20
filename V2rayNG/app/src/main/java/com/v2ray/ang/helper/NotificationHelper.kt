package com.v2ray.ang.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType

/**
 * Unified notification helper for different notification channels.
 * Supports both regular notifications and foreground service notifications.
 *
 * Performance: NotificationManager is cached. Builder is created once per update.
 * Safe for high-frequency updates (100+ times/second).
 */
object NotificationHelper {

    // Cached instances for performance
    private var cachedNotificationManager: NotificationManager? = null
    private val builderCache = mutableMapOf<Int, NotificationCompat.Builder>()
    private val dynamicNotificationIds = mutableMapOf<String, Int>()
    private val builderLock = Any()

    /** Allocates a collision-free notification id for the lifetime of one process. */
    fun allocateNotificationId(key: String): Int = synchronized(builderLock) {
        dynamicNotificationIds[key]?.let { return@synchronized it }

        val start = DYNAMIC_NOTIFICATION_ID_BASE +
            (key.hashCode() and Int.MAX_VALUE) % DYNAMIC_NOTIFICATION_ID_RANGE
        var candidate = start
        repeat(DYNAMIC_NOTIFICATION_ID_RANGE) {
            if (candidate !in dynamicNotificationIds.values) {
                dynamicNotificationIds[key] = candidate
                return@synchronized candidate
            }
            candidate = if (candidate == DYNAMIC_NOTIFICATION_ID_BASE + DYNAMIC_NOTIFICATION_ID_RANGE - 1) {
                DYNAMIC_NOTIFICATION_ID_BASE
            } else {
                candidate + 1
            }
        }
        error("No notification id available for $key")
    }

    /** Releases an id allocated by [allocateNotificationId]. */
    fun releaseNotificationId(key: String) {
        synchronized(builderLock) {
            dynamicNotificationIds.remove(key)
        }
    }

    /**
     * Notify with a regular notification (non-foreground).
     *
     * @param channelType The notification channel type (defines channelId, notificationId, etc.)
     * @param context The context for building the notification
     * @param title The notification title
     * @param content The notification content text
     */
    fun notify(
        channelType: NotificationChannelType,
        context: Context,
        title: String,
        content: String,
        notificationId: Int = channelType.notificationId,
    ) {
        ensureChannelCreated(channelType, context)
        val notificationManager = getNotificationManager(context)
        val builder = buildNotificationBuilder(channelType, context, title, content)
        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Update an existing notification's content.
     * Optimized for high-frequency updates (100+/sec).
     * Reuses cached Builder to minimize allocation overhead.
     *
     * @param channelType The notification channel type
     * @param context The context
     * @param content The new content text
     */
    fun updateNotification(
        channelType: NotificationChannelType,
        context: Context,
        title: String,
        content: String,
        notificationId: Int = channelType.notificationId,
    ) {
        val notificationManager = getNotificationManager(context)

        val notification = synchronized(builderLock) {
            val builder = builderCache.getOrPut(notificationId) {
                buildNotificationBuilder(channelType, context, title, content)
            }
            builder.setContentTitle(title.ifEmpty { context.getString(R.string.app_name) })
            builder.setContentText(content)
            builder.build()
        }
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Start a foreground service with a notification.
     *
     * @param service The service to set as foreground
     * @param channelType The notification channel type
     * @param title The notification title
     * @param content The notification content text
     * @param action An optional action retained when the notification is updated
     */
    fun startForeground(
        service: Service,
        channelType: NotificationChannelType,
        title: String,
        content: String,
        action: NotificationCompat.Action? = null
    ) {
        ensureChannelCreated(channelType, service)
        val builder = buildNotificationBuilder(channelType, service, title, content, action)
        synchronized(builderLock) {
            builderCache[channelType.notificationId] = builder
        }
        service.startForeground(channelType.notificationId, builder.build())
    }

    /**
     * Builds the foreground descriptor used by a long-running WorkManager worker.
     *
     * WorkManager owns the actual foreground service for a Worker, so callers cannot pass a
     * [Service] instance to [startForeground]. Keep the notification construction here so the
     * worker and the legacy foreground services use the same channel, id and cached builder.
     */
    fun foregroundInfo(
        channelType: NotificationChannelType,
        context: Context,
        title: String,
        content: String,
        action: NotificationCompat.Action? = null,
        notificationId: Int = channelType.notificationId,
    ): ForegroundInfo {
        ensureChannelCreated(channelType, context)
        val builder = buildNotificationBuilder(channelType, context, title, content, action)
        synchronized(builderLock) {
            builderCache[notificationId] = builder
        }
        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Stop the foreground notification for a service.
     *
     * @param service The service to stop foreground on
     */
    fun stopForeground(service: Service) {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Cancel a notification and clean up cached builder.
     *
     * @param channelType The notification channel type
     * @param context The context
     */
    fun cancel(
        channelType: NotificationChannelType,
        context: Context,
        notificationId: Int = channelType.notificationId,
    ) {
        try {
            getNotificationManager(context).cancel(notificationId)
        } finally {
            synchronized(builderLock) {
                builderCache.remove(notificationId)
            }
        }
    }

    // ====== Private helper methods ======

    private fun getNotificationManager(context: Context): NotificationManager {
        return synchronized(builderLock) {
            if (cachedNotificationManager == null) {
                cachedNotificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            }
            cachedNotificationManager!!
        }
    }

    private fun ensureChannelCreated(channelType: NotificationChannelType, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = getNotificationManager(context)
        val channelName = context.getString(channelType.channelNameRes)
        val channel = notificationManager.getNotificationChannel(channelType.channelId)
        if (channel == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    channelType.channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        } else if (channel.name != channelName) {
            channel.name = channelName
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotificationBuilder(
        channelType: NotificationChannelType,
        context: Context,
        title: String,
        content: String,
        action: NotificationCompat.Action? = null
    ): NotificationCompat.Builder {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelType.channelId
        } else {
            ""
        }

        val displayTitle = title.ifEmpty { context.getString(R.string.app_name) }
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(displayTitle)
            .setContentText(content)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply { action?.let(::addAction) }
    }

    private const val DYNAMIC_NOTIFICATION_ID_BASE = 10_000
    private const val DYNAMIC_NOTIFICATION_ID_RANGE = 100_000
}
