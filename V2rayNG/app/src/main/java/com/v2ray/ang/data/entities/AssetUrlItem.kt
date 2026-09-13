package com.v2ray.ang.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "assets")
data class AssetUrlItem(
    @PrimaryKey val guid: String = "",
    var remarks: String = "",
    var url: String = "",
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var locked: Boolean? = false,
)
