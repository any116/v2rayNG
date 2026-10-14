package com.v2ray.ang.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * The part of a profile that participates in list queries, ordering, searching, and grouping.
 * Protocol-only changes must not update this table: Room invalidates PagingSources by table.
 */
@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["subscriptionId", "groupSortOrder", "sortOrder", "guid"]),
        Index(value = ["groupSortOrder", "sortOrder", "guid"]),
        Index(value = ["remarks"]),
    ]
)
data class ProfileMetadataItem(
    @PrimaryKey
    val guid: String,
    val sortOrder: Long,
    @ColumnInfo(defaultValue = "9223372036854775807")
    val groupSortOrder: Long = Long.MAX_VALUE,
    val configType: Int,
    val subscriptionId: String,
    val addedTime: Long,
    val remarks: String,
    val description: String?,
    val server: String?,
    val serverPort: String?,
    val network: String?,
    val security: String?,
    val insecure: Boolean?,
)
