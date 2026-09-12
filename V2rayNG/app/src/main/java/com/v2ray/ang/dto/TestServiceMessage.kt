package com.v2ray.ang.dto

import java.io.Serializable

/**
 * UI -> CoreTestService command.
 * [requestId] identifies one batch request; an empty id on a cancel means "cancel every request".
 */
data class TestServiceMessage(
    val key: Int,
    val requestId: String = "",
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val onlyTcp: Boolean = false
) : Serializable
