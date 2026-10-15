package com.v2ray.ang.data.dao

import com.v2ray.ang.enums.EConfigType

// ---- Projections: list queries never touch profile_raw ----

data class ServerRowProjection(
    val guid: String,
    val remarks: String,
    val description: String?,
    val server: String?,
    val serverPort: String?,
    val configType: EConfigType,
    val network: String?,
    val security: String?,
    val insecure: Boolean?,
    val subscriptionId: String,
    val subscriptionInitial: String?,
    val testDelayMillis: Long,
)

data class GroupCount(val groupId: String, val count: Int)

data class RemarkRow(val remarks: String)

data class SortAnchor(val guid: String, val sortOrder: Long)

data class DefaultGroupRepair(val createdDefault: Boolean, val adoptedProfiles: Int)
