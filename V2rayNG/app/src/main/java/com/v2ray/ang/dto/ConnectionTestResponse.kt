package com.v2ray.ang.dto

import java.io.Serializable

/** Daemon -> UI reply of one current-server test request. */
data class ConnectionTestResponse(
    val requestId: String,
    val result: ConnectionTestResult
) : Serializable
