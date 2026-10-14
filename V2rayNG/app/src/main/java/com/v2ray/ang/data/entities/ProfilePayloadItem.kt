package com.v2ray.ang.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Versioned complete profile payload. It is intentionally not foreign-key constrained. */
@Entity(
    tableName = "profile_payload",
    indices = [Index(value = ["dedupeKey"])]
)
data class ProfilePayloadItem(
    @PrimaryKey
    val guid: String,
    val payloadVersion: Int,
    val content: String,
    @ColumnInfo(defaultValue = "")
    val dedupeKey: String = "",
)
