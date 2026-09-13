package com.v2ray.ang.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "profile_stats")
data class ServerAffiliationInfo(
    @PrimaryKey val guid: String = "",
    var testDelayMillis: Long = 0L,
)
