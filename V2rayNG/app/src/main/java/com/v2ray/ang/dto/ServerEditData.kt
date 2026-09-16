package com.v2ray.ang.dto

import com.v2ray.ang.data.entities.ProfileItem

data class ServerEditData(
    val profile: ProfileItem?,
    val isSelected: Boolean = false,
    val rawContent: String = "",
    val subscriptions: List<SubscriptionOption> = emptyList(),
)
