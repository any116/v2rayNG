package com.v2ray.ang.data.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.v2ray.ang.data.entities.SettingsEntry

@Dao
interface SettingsDao {

    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingsEntry>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun value(key: String): String?

    @Upsert
    suspend fun upsert(entry: SettingsEntry)

    @Upsert
    suspend fun upsertAll(entries: List<SettingsEntry>)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}
