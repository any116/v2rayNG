package com.v2ray.ang.data.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.SubscriptionItem
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY sortOrder, guid")
    fun observeAll(): Flow<List<SubscriptionItem>>

    @Query("SELECT * FROM subscriptions ORDER BY sortOrder, guid")
    suspend fun all(): List<SubscriptionItem>

    @Query("SELECT guid FROM subscriptions ORDER BY sortOrder, guid")
    suspend fun allGuids(): List<String>

    @Query("SELECT * FROM subscriptions WHERE guid = :guid")
    suspend fun find(guid: String): SubscriptionItem?

    @Query("SELECT * FROM subscriptions WHERE enabled = 1 AND autoUpdate = 1 ORDER BY sortOrder, guid")
    suspend fun autoUpdatable(): List<SubscriptionItem>

    @Query("SELECT IFNULL(MAX(sortOrder), 0) FROM subscriptions")
    suspend fun maxSortOrder(): Long

    @Query("SELECT IFNULL(MIN(sortOrder), 0) FROM subscriptions")
    suspend fun minSortOrder(): Long

    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(item: SubscriptionItem)

    @Upsert
    suspend fun upsertAll(items: List<SubscriptionItem>)

    @Query("UPDATE subscriptions SET sortOrder = :sortOrder WHERE guid = :guid")
    suspend fun setSortOrder(guid: String, sortOrder: Long)

    @Query("UPDATE subscriptions SET lastUpdated = :time WHERE guid = :guid")
    suspend fun touch(guid: String, time: Long)

    @Query("DELETE FROM subscriptions WHERE guid = :guid")
    suspend fun deleteRow(guid: String)

    @Query("SELECT guid FROM profiles WHERE subscriptionId = :subscriptionId")
    suspend fun profileGuidsOf(subscriptionId: String): List<String>

    @Query("DELETE FROM profiles WHERE subscriptionId = :subscriptionId")
    suspend fun deleteProfileRowsOf(subscriptionId: String)

    @Query("DELETE FROM profile_stats WHERE guid IN (:guids)")
    suspend fun deleteStatsOf(guids: List<String>)

    @Query("DELETE FROM profile_raw WHERE guid IN (:guids)")
    suspend fun deleteRawsOf(guids: List<String>)

    @Query("DELETE FROM profile_payload WHERE guid IN (:guids)")
    suspend fun deletePayloadsOf(guids: List<String>)

    @Query("SELECT COUNT(*) FROM profiles WHERE subscriptionId NOT IN (SELECT guid FROM subscriptions)")
    suspend fun orphanProfileCount(): Int

    @Query("UPDATE profiles SET subscriptionId = :target WHERE subscriptionId NOT IN (SELECT guid FROM subscriptions)")
    suspend fun adoptOrphanProfiles(target: String)

    @Query("SELECT value FROM settings WHERE key = 'SELECTED_SERVER'")
    suspend fun selectedGuid(): String?

    @Query("INSERT OR REPLACE INTO settings(key, value, kind) VALUES ('SELECTED_SERVER', :guid, 's')")
    suspend fun putSelectedGuid(guid: String)

    @Query("DELETE FROM settings WHERE key = 'SELECTED_SERVER'")
    suspend fun clearSelectedGuid()

    /** Lowest-priority profile after this subscription was removed, in global list order. */
    @Query("SELECT guid FROM profiles ORDER BY groupSortOrder, sortOrder, guid LIMIT 1")
    suspend fun firstProfileGuid(): String?

    @Transaction
    suspend fun insertAtEnd(item: SubscriptionItem) {
        upsert(item.copy(sortOrder = maxSortOrder() + ProfileItem.SORT_STEP))
    }

    /** Subscriptions number in the tens; rewriting the whole order on drag end is simplest. */
    @Transaction
    suspend fun saveOrder(guids: List<String>) {
        guids.forEachIndexed { index, guid -> setSortOrder(guid, (index + 1) * ProfileItem.SORT_STEP) }
    }

    @Transaction
    suspend fun deleteProfilesOf(subscriptionId: String) {
        val guids = profileGuidsOf(subscriptionId)
        guids.chunked(ProfileDao.SQLITE_VAR_LIMIT).forEach {
            deleteStatsOf(it)
            deleteRawsOf(it)
            deletePayloadsOf(it)
        }
        deleteProfileRowsOf(subscriptionId)
    }

    @Transaction
    suspend fun ensureDefault(defaultRemarks: String): DefaultGroupRepair =
        repairDefault(defaultRemarks, force = false)

    @Transaction
    suspend fun ensureDefaultForced(defaultRemarks: String): DefaultGroupRepair =
        repairDefault(defaultRemarks, force = true)

    @Transaction
    suspend fun repairDefault(defaultRemarks: String, force: Boolean): DefaultGroupRepair {
        var exists = find(AppConfig.DEFAULT_SUBSCRIPTION_ID) != null
        val orphans = orphanProfileCount()
        // Read once; both former call sites read it before the upsert below, so one read is
        // equivalent — and unlike the old shape the two checks can never disagree.
        val total = count()
        val created = !exists && (force || orphans > 0 || total == 0)
        if (created) {
            val head = if (total == 0) {
                ProfileItem.SORT_STEP
            } else {
                minSortOrder() - ProfileItem.SORT_STEP
            }
            upsert(
                SubscriptionItem(
                    guid = AppConfig.DEFAULT_SUBSCRIPTION_ID,
                    sortOrder = head,
                    remarks = defaultRemarks,
                )
            )
            exists = true
        }
        val adopted = if (exists && orphans > 0) {
            adoptOrphanProfiles(AppConfig.DEFAULT_SUBSCRIPTION_ID)
            orphans
        } else {
            0
        }
        return DefaultGroupRepair(createdDefault = created, adoptedProfiles = adopted)
    }

    @Transaction
    suspend fun removeWithDefault(guid: String, defaultRemarks: String) {
        val guids = profileGuidsOf(guid)
        val hadSelection = selectedGuid() in guids
        deleteProfilesOf(guid)
        deleteRow(guid)
        ensureDefault(defaultRemarks)
        if (hadSelection) {
            val fallback = firstProfileGuid()
            if (fallback.isNullOrBlank()) clearSelectedGuid() else putSelectedGuid(fallback)
        }
    }
}
