package com.v2ray.ang.dto

import androidx.compose.runtime.Immutable

/** Identity is assigned before filtering, so a record keeps its key across searches and refreshes. */
@Immutable
data class LogcatRecord(
    val id: Long,
    val raw: String
)
