package com.v2ray.ang.data.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.v2ray.ang.data.entities.AssetUrlItem
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {

    @Query("SELECT * FROM assets ORDER BY addedTime")
    fun observeAll(): Flow<List<AssetUrlItem>>

    @Query("SELECT * FROM assets ORDER BY addedTime")
    suspend fun all(): List<AssetUrlItem>

    @Query("SELECT * FROM assets WHERE guid = :guid")
    suspend fun find(guid: String): AssetUrlItem?

    @Query("SELECT * FROM assets WHERE remarks = :remarks LIMIT 1")
    suspend fun findByRemarks(remarks: String): AssetUrlItem?

    @Upsert
    suspend fun upsert(item: AssetUrlItem)

    @Query("UPDATE assets SET lastUpdated = :time WHERE guid = :guid")
    suspend fun touch(guid: String, time: Long)

    @Query("DELETE FROM assets WHERE guid = :guid AND IFNULL(locked, 0) = 0")
    suspend fun delete(guid: String)
}
