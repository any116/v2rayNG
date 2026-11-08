package com.v2ray.ang.handler

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import com.v2ray.ang.R

/** Only notification data is retained across updates, never a mutable platform builder or style. */
internal data class CoreNotificationContent(
    val title: String? = null,
    val text: String? = null,
    @param:DrawableRes val icon: Int = R.drawable.ic_stat_name
)

/**
 * Every post is self-contained so SystemUI can reinflate it after a night-mode change or long idle.
 * Actions belong to the service, not to the optional traffic text or its expanded style.
 */
internal fun buildCoreNotification(
    context: Context,
    channelId: String,
    content: CoreNotificationContent,
    contentIntent: PendingIntent,
    stopIntent: PendingIntent,
    restartIntent: PendingIntent
): Notification {
    val text = content.text?.takeIf { it.isNotBlank() }
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(content.icon)
        .setContentTitle(content.title ?: context.resources.getString(R.string.app_name))
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setShowWhen(false)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .addAction(
            R.drawable.ic_delete_24dp,
            context.resources.getString(R.string.notification_action_stop_v2ray),
            stopIntent
        )
        .addAction(
            R.drawable.ic_restore_24dp,
            context.resources.getString(R.string.title_service_restart),
            restartIntent
        )
        .apply {
            // With no traffic text, use the standard action-bearing template, not an empty style.
            if (text != null) setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        .build()
}
