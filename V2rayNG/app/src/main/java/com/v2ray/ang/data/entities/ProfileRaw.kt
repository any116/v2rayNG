package com.v2ray.ang.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Raw config text for CUSTOM profiles. Deliberately a separate table with no foreign key:
 * PRAGMA foreign_keys=ON would add a constraint check to every high-frequency stats write in
 * the :tasks process, and deletion already funnels through ProfileDao.deleteProfiles().
 */
@Entity(tableName = "profile_raw")
data class ProfileRaw(
    @PrimaryKey val guid: String,
    val content: String,
)
