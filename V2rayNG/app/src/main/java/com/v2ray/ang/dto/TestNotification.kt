package com.v2ray.ang.dto

import java.io.Serializable

/**
 * Service -> UI notification about one batch request.
 * [payload] carries the progress text for notify events and the guid for success events.
 */
data class TestNotification(
    val requestId: String,
    val payload: String = ""
) : Serializable
