package com.v2ray.ang.data.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.RulesetItem
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface RoutingDao {

    @Query("SELECT * FROM routing_rules ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<RulesetItem>>

    @Query("SELECT * FROM routing_rules ORDER BY sortOrder, id")
    suspend fun all(): List<RulesetItem>

    @Query("SELECT * FROM routing_rules WHERE enabled = 1 ORDER BY sortOrder, id")
    suspend fun enabled(): List<RulesetItem>

    @Query("SELECT * FROM routing_rules WHERE id = :id")
    suspend fun find(id: String): RulesetItem?

    @Query("SELECT id FROM routing_rules")
    suspend fun allIds(): List<String>

    @Query("SELECT IFNULL(MAX(sortOrder), 0) FROM routing_rules")
    suspend fun maxSortOrder(): Long

    @Query("SELECT COUNT(*) FROM routing_rules")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(rule: RulesetItem)

    @Upsert
    suspend fun upsertAll(rules: List<RulesetItem>)

    @Query("UPDATE routing_rules SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Long)

    @Query("DELETE FROM routing_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM routing_rules WHERE IFNULL(locked, 0) = 0")
    suspend fun deleteUnlocked()

    @Transaction
    suspend fun insertAtEnd(rule: RulesetItem) {
        upsert(rule.copy(sortOrder = maxSortOrder() + ProfileItem.SORT_STEP))
    }

    @Transaction
    suspend fun saveOrder(ids: List<String>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, (index + 1) * ProfileItem.SORT_STEP) }
    }

    /** Replaces unlocked rules and appends imports without overwriting retained rules. */
    @Transaction
    suspend fun replaceAll(rules: List<RulesetItem>) {
        deleteUnlocked()
        val base = maxSortOrder()
        val usedIds = allIds().toMutableSet()
        // Keep generated IDs clear of IDs that later imports should retain.
        val reservedIds = rules.mapTo(usedIds.toMutableSet()) { it.id }
        upsertAll(
            rules.mapIndexed { index, rule ->
                val originalId = rule.id
                val id = if (originalId.isNullOrBlank() || !usedIds.add(originalId)) {
                    generateSequence { UUID.randomUUID().toString() }.first { reservedIds.add(it) }
                } else {
                    originalId
                }
                rule.copy(id = id, sortOrder = base + (index + 1) * ProfileItem.SORT_STEP)
            }
        )
    }
}
