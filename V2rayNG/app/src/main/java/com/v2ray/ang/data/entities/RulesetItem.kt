package com.v2ray.ang.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "routing_rules")
data class RulesetItem(
    @PrimaryKey var id: String = "",
    var sortOrder: Long = 0L,
    var remarks: String? = "",
    var ip: List<String>? = null,
    var domain: List<String>? = null,
    var process: List<String>? = null,
    var outboundTag: String = "",
    var port: String? = null,
    var network: String? = null,
    var protocol: List<String>? = null,
    var enabled: Boolean = true,
    var locked: Boolean? = false,
)
