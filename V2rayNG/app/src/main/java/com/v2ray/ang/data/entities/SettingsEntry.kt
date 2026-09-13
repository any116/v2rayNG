package com.v2ray.ang.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Scalar preference row.
 */
@Entity(tableName = "settings")
data class SettingsEntry(
    @PrimaryKey val key: String,
    val value: String?,
    val kind: String,
)
