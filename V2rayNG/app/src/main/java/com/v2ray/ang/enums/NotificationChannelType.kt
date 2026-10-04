package com.v2ray.ang.enums

import com.v2ray.ang.R

/**
 * Enum defining different notification channels.
 * Each channel has a unique channelId, notificationId, and display name.
 */
enum class NotificationChannelType(
    val channelId: String,
    val channelNameRes: Int,
    val notificationId: Int
) {
    SUBSCRIPTION_UPDATE(
        channelId = "subscription_update_channel",
        channelNameRes = R.string.notification_channel_subscription_update,
        notificationId = 13
    ),
    CORE_TEST(
        channelId = "core_test_channel",
        channelNameRes = R.string.notification_channel_core_test,
        notificationId = 12
    )
}
