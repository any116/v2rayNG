package com.v2ray.ang.data

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.data.entities.SettingsEntry
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.flow.Flow

// ---- Projections: list queries never touch profile_raw ----

data class ServerRowProjection(
    val guid: String,
    val remarks: String,
    val description: String?,
    val server: String?,
    val serverPort: String?,
    val configType: EConfigType,
    val network: String?,
    val security: String?,
    val insecure: Boolean?,
    val subscriptionId: String,
    val subscriptionInitial: String?,
    val testDelayMillis: Long,
)

data class GroupCount(val groupId: String, val count: Int)

data class RemarkRow(val remarks: String)

data class SortAnchor(val guid: String, val sortOrder: Long)

data class DefaultGroupRepair(val createdDefault: Boolean, val adoptedProfiles: Int)

/**
 * Single source of truth for list ordering. pageServers / indexOf / neighboursAt /
 * allGuidsInOrder must stay character-identical, and ProfilePagingTest asserts that they
 * agree index by index.
 */
internal const val SERVER_ORDER =
    "IFNULL(s.sortOrder, 9223372036854775807), p.sortOrder, p.guid"

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
interface ProfileDao {

    // ---- Paging: the only entry point for the main list ----
    // subscriptionId = '' means the "All" group.
    // query is already lowercased and LIKE-escaped; callers must use String.normalizeLike().
    @Query(
        """
        SELECT p.guid           AS guid,
               p.remarks        AS remarks,
               p.description    AS description,
               p.server         AS server,
               p.serverPort     AS serverPort,
               p.configType     AS configType,
               p.network        AS network,
               p.security       AS security,
               p.insecure       AS insecure,
               p.subscriptionId AS subscriptionId,
               UPPER(SUBSTR(s.remarks, 1, 1)) AS subscriptionInitial,
               IFNULL(st.testDelayMillis, 0)  AS testDelayMillis
          FROM profiles AS p
          LEFT JOIN subscriptions AS s  ON s.guid = p.subscriptionId
          LEFT JOIN profile_stats AS st ON st.guid = p.guid
         WHERE (:subscriptionId = '' OR p.subscriptionId = :subscriptionId)
           AND (:query = ''
                OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
         ORDER BY IFNULL(s.sortOrder, 9223372036854775807), p.sortOrder, p.guid
        """
    )
    fun pageServers(subscriptionId: String, query: String): PagingSource<Int, ServerRowProjection>

    // ---- Counts: drives the group tab badges ----
    // Driven from subscriptions via a correlated subquery so an EMPTY group still yields
    // count = 0. GROUP BY p.subscriptionId would drop empty groups.
    @Query(
        """
        SELECT s.guid AS groupId,
               (SELECT COUNT(*) FROM profiles AS p
                 WHERE p.subscriptionId = s.guid
                   AND (:query = ''
                        OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                        OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                        OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
               ) AS count
          FROM subscriptions AS s
        UNION ALL
        SELECT p.subscriptionId AS groupId, COUNT(*) AS count
          FROM profiles AS p
         WHERE p.subscriptionId NOT IN (SELECT guid FROM subscriptions)
           AND (:query = ''
                OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
         GROUP BY p.subscriptionId
        """
    )
    fun observeCounts(query: String): Flow<List<GroupCount>>

    @Query(
        """
        SELECT COUNT(*) FROM profiles AS p
         WHERE (:query = ''
                OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
        """
    )
    fun observeTotalCount(query: String): Flow<Int>

    // ---- Locate: ROW_NUMBER needs SQLite >= 3.25, guaranteed by BundledSQLiteDriver ----
    // Must take :query — pageServers' itemCount is post-filter, so an index computed without
    // the search term jumps to the wrong row while searching.
    @Query(
        """
        SELECT rn - 1 FROM (
            SELECT p.guid AS g,
                   ROW_NUMBER() OVER (
                       ORDER BY IFNULL(s.sortOrder, 9223372036854775807), p.sortOrder, p.guid
                   ) AS rn
              FROM profiles AS p
              LEFT JOIN subscriptions AS s ON s.guid = p.subscriptionId
             WHERE (:subscriptionId = '' OR p.subscriptionId = :subscriptionId)
               AND (:query = ''
                    OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                    OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                    OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
        ) WHERE g = :guid
        """
    )
    suspend fun indexOf(subscriptionId: String, query: String, guid: String): Int?

    /**
     * Two guids starting at :offset in list order, excluding the dragged row. Drag targets are
     * resolved here rather than from LazyColumn layoutInfo: with placeholders enabled a
     * placeholder row's key is a PagingPlaceholderKey, not the profile guid.
     */
    @Query(
        """
        SELECT p.guid FROM profiles AS p
          LEFT JOIN subscriptions AS s ON s.guid = p.subscriptionId
         WHERE (:subscriptionId = '' OR p.subscriptionId = :subscriptionId)
           AND p.guid <> :excludeGuid
           AND (:query = ''
                OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
         ORDER BY IFNULL(s.sortOrder, 9223372036854775807), p.sortOrder, p.guid
         LIMIT 2 OFFSET :offset
        """
    )
    suspend fun neighboursAt(
        subscriptionId: String,
        query: String,
        excludeGuid: String,
        offset: Int,
    ): List<String>

    // ---- Single row ----

    @Query("SELECT * FROM profiles WHERE guid = :guid")
    suspend fun findByGuid(guid: String): ProfileItem?

    /**
     * Routing outbound tags, proxy chain nodes and policy-group fallback tags are all resolved
     * by remark. Duplicate remarks are common in subscriptions, so the ORDER BY is not
     * cosmetic: without it the row SQLite happens to return first is unspecified, and the
     * core config would drift between launches.
     */
    @Query("SELECT * FROM profiles WHERE remarks = :remarks ORDER BY sortOrder, guid LIMIT 1")
    suspend fun findByRemarks(remarks: String): ProfileItem?

    @Query(
        """
        SELECT remarks FROM profiles
         WHERE configType NOT IN (:excludeTypes) AND TRIM(remarks) <> ''
         GROUP BY remarks
         ORDER BY MIN(sortOrder), remarks
        """
    )
    suspend fun remarks(excludeTypes: List<Int>): List<String>

    /**
     * Data source for FormPagedDropdownField. GROUP BY (not SELECT DISTINCT) because SQLite
     * rejects an ORDER BY term that is absent from a DISTINCT select list, and grouping lets us
     * order by MIN(sortOrder) while still emitting one row per remark for LazyColumn keys.
     */
    @Query(
        """
        SELECT p.remarks AS remarks
          FROM profiles AS p
         WHERE p.configType NOT IN (:excludeTypes)
           AND TRIM(p.remarks) <> ''
           AND (:query = '' OR LOWER(p.remarks) LIKE '%' || :query || '%' ESCAPE '\')
         GROUP BY p.remarks
         ORDER BY MIN(p.sortOrder), p.remarks
        """
    )
    fun pageRemarks(excludeTypes: List<Int>, query: String): PagingSource<Int, RemarkRow>

    @Query("SELECT guid FROM profiles WHERE subscriptionId = :subscriptionId ORDER BY sortOrder, guid")
    suspend fun guidsInGroup(subscriptionId: String): List<String>

    /**
     * Profiles of one scope, complex types excluded. Replaces the pre-Room
     * decodeAllServerList() + per-guid decode loop used for POLICYGROUP resolution, which walked
     * the entire table. subscriptionId = '' means "all groups".
     */
    @Query(
        """
        SELECT * FROM profiles
         WHERE (:subscriptionId = '' OR subscriptionId = :subscriptionId)
           AND configType NOT IN (:complexTypes)
         ORDER BY sortOrder, guid
        """
    )
    suspend fun profilesOfScope(subscriptionId: String, complexTypes: List<Int>): List<ProfileItem>

    @Query(
        """
        SELECT p.guid FROM profiles AS p
          LEFT JOIN subscriptions AS s ON s.guid = p.subscriptionId
         ORDER BY IFNULL(s.sortOrder, 9223372036854775807), p.sortOrder, p.guid
        """
    )
    suspend fun allGuidsInOrder(): List<String>

    /** Guids of the currently visible set, in list order. Backs export / batch test / scoped delete. */
    @Query(
        """
        SELECT p.guid FROM profiles AS p
          LEFT JOIN subscriptions AS s ON s.guid = p.subscriptionId
         WHERE (:subscriptionId = '' OR p.subscriptionId = :subscriptionId)
           AND (:query = ''
                OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
         ORDER BY IFNULL(s.sortOrder, 9223372036854775807), p.sortOrder, p.guid
        """
    )
    suspend fun guidsInScope(subscriptionId: String, query: String): List<String>

    @Query("SELECT subscriptionId FROM profiles WHERE guid = :guid")
    suspend fun subscriptionIdOf(guid: String): String?

    @Upsert
    suspend fun upsert(profile: ProfileItem)

    @Upsert
    suspend fun upsertAll(profiles: List<ProfileItem>)

    // ---- SELECTED_SERVER, written inside the same transaction as profile writes/deletes ----

    @Query("SELECT value FROM settings WHERE key = 'SELECTED_SERVER'")
    suspend fun selectedGuid(): String?

    @Query("INSERT OR REPLACE INTO settings(key, value, kind) VALUES ('SELECTED_SERVER', :guid, 's')")
    suspend fun putSelectedGuid(guid: String)

    @Query("DELETE FROM settings WHERE key = 'SELECTED_SERVER'")
    suspend fun clearSelectedGuid()

    suspend fun writeSelectedGuid(guid: String?) {
        if (guid.isNullOrBlank()) clearSelectedGuid() else putSelectedGuid(guid)
    }

    // ---- Ordering: sparse sortOrder with midpoint insertion ----

    @Query("SELECT IFNULL(MIN(sortOrder), 0) FROM profiles WHERE subscriptionId = :subscriptionId")
    suspend fun minSortOrder(subscriptionId: String): Long

    @Query("SELECT IFNULL(MAX(sortOrder), 0) FROM profiles WHERE subscriptionId = :subscriptionId")
    suspend fun maxSortOrder(subscriptionId: String): Long

    @Query("SELECT guid, sortOrder FROM profiles WHERE guid IN (:guids)")
    suspend fun sortAnchors(guids: List<String>): List<SortAnchor>

    @Query("UPDATE profiles SET sortOrder = :sortOrder WHERE guid = :guid")
    suspend fun setSortOrder(guid: String, sortOrder: Long)

    /**
     * Drag drop. targetIndex is the index AFTER removing the dragged row (reorderable's
     * to.index). Neighbours come from neighboursAt, not from the UI.
     *
     * Only valid for a concrete group: in the "All" view neighbours can live in different
     * subscriptions and a single sortOrder update cannot express that position, so the caller
     * must disable reordering there. Guarded rather than silently mis-ordering.
     */
    @Transaction
    suspend fun moveProfileToIndex(
        subscriptionId: String,
        query: String,
        movedGuid: String,
        targetIndex: Int,
    ) {
        if (subscriptionId.isEmpty()) return
        if (subscriptionIdOf(movedGuid) != subscriptionId) return

        val offset = (targetIndex - 1).coerceAtLeast(0)
        val window = neighboursAt(subscriptionId, query, movedGuid, offset)
        val prevGuid = if (targetIndex == 0) null else window.getOrNull(0)
        val nextGuid = if (targetIndex == 0) window.getOrNull(0) else window.getOrNull(1)

        val anchors = sortAnchors(listOfNotNull(prevGuid, nextGuid)).associate { it.guid to it.sortOrder }
        val prev = prevGuid?.let { anchors[it] }
        val next = nextGuid?.let { anchors[it] }

        val target = when {
            prev != null && next != null -> {
                if (next - prev < 2L) {
                    renormalize(subscriptionId)
                    return moveProfileToIndex(subscriptionId, query, movedGuid, targetIndex)
                }
                prev + (next - prev) / 2
            }
            prev != null -> prev + ProfileItem.SORT_STEP
            next != null -> next - ProfileItem.SORT_STEP
            else -> ProfileItem.SORT_STEP
        }
        setSortOrder(movedGuid, target)
    }

    @Transaction
    suspend fun renormalize(subscriptionId: String) {
        guidsInGroup(subscriptionId).forEachIndexed { index, guid ->
            setSortOrder(guid, (index + 1) * ProfileItem.SORT_STEP)
        }
    }

    /** Untested or failed entries (delay <= 0) sink to the bottom. */
    @Query(
        """
        SELECT p.guid FROM profiles AS p
          LEFT JOIN profile_stats AS st ON st.guid = p.guid
         WHERE p.subscriptionId = :subscriptionId
         ORDER BY CASE WHEN IFNULL(st.testDelayMillis, 0) <= 0
                       THEN 9223372036854775807
                       ELSE st.testDelayMillis END,
                  p.sortOrder, p.guid
        """
    )
    suspend fun guidsByDelay(subscriptionId: String): List<String>

    @Transaction
    suspend fun sortByDelay(subscriptionId: String) {
        guidsByDelay(subscriptionId).forEachIndexed { index, guid ->
            setSortOrder(guid, (index + 1) * ProfileItem.SORT_STEP)
        }
    }

    // ---- Deletion: all three tables together ----

    @Query("DELETE FROM profiles      WHERE guid IN (:guids)")
    suspend fun deleteProfileRows(guids: List<String>)

    @Query("DELETE FROM profile_stats WHERE guid IN (:guids)")
    suspend fun deleteStatRows(guids: List<String>)

    @Query("DELETE FROM profile_raw   WHERE guid IN (:guids)")
    suspend fun deleteRawRows(guids: List<String>)

    /**
     * The only profile deletion exit. Also repairs the long-standing "selection dangles after
     * deleting the selected profile" bug, inside the same transaction.
     */
    @Transaction
    suspend fun deleteProfiles(guids: List<String>): Int {
        if (guids.isEmpty()) return 0
        val selected = selectedGuid()
        val group = if (selected != null && selected in guids) subscriptionIdOf(selected) else null

        guids.chunked(SQLITE_VAR_LIMIT).forEach {
            deleteProfileRows(it)
            deleteStatRows(it)
            deleteRawRows(it)
        }

        if (group != null) {
            writeSelectedGuid(guidsInGroup(group).firstOrNull() ?: allGuidsInOrder().firstOrNull())
        }
        return guids.size
    }

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Query("DELETE FROM profiles")
    suspend fun clearProfiles()

    @Query("DELETE FROM profile_stats")
    suspend fun clearStats()

    @Query("DELETE FROM profile_raw")
    suspend fun clearRaw()

    @Transaction
    suspend fun deleteAll(): Int {
        val removed = count()
        clearProfiles()
        clearStats()
        clearRaw()
        writeSelectedGuid(null)
        return removed
    }

    // ---- Cleanup: invalid / duplicate ----
    // Both carry :query so "only act on currently visible rows" keeps the pre-Room semantics.

    @Query(
        """
        SELECT p.guid FROM profiles AS p
          JOIN profile_stats AS st ON st.guid = p.guid
         WHERE st.testDelayMillis < 0
           AND (:subscriptionId = '' OR p.subscriptionId = :subscriptionId)
           AND (:query = ''
                OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
        """
    )
    suspend fun invalidGuids(subscriptionId: String, query: String): List<String>

    /**
     * Duplicates: per dedupeKey keep the lowest sortOrder. The correlated subquery repeats the
     * scope filters so the surviving row is chosen from the SAME visible set — otherwise a
     * keeper in another group or filtered out by the search term would cause every visible
     * copy to be deleted.
     */
    @Query(
        """
        SELECT p.guid FROM profiles AS p
         WHERE p.configType NOT IN (:complexTypes)
           AND p.dedupeKey <> ''
           AND (:subscriptionId = '' OR p.subscriptionId = :subscriptionId)
           AND (:query = ''
                OR LOWER(p.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                OR LOWER(IFNULL(p.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
           AND p.guid <> (
                 SELECT q.guid FROM profiles AS q
                  WHERE q.dedupeKey = p.dedupeKey
                    AND q.configType NOT IN (:complexTypes)
                    AND (:subscriptionId = '' OR q.subscriptionId = :subscriptionId)
                    AND (:query = ''
                         OR LOWER(q.remarks)                LIKE '%' || :query || '%' ESCAPE '\'
                         OR LOWER(IFNULL(q.description,'')) LIKE '%' || :query || '%' ESCAPE '\'
                         OR LOWER(IFNULL(q.server,''))      LIKE '%' || :query || '%' ESCAPE '\')
                  ORDER BY q.sortOrder, q.guid LIMIT 1)
        """
    )
    suspend fun duplicateGuids(
        subscriptionId: String,
        query: String,
        complexTypes: List<Int>,
    ): List<String>

    // ---- dedupeKey lazy backfill ----

    @Query(
        """
        SELECT * FROM profiles
         WHERE dedupeKey = ''
           AND (:subscriptionId = '' OR subscriptionId = :subscriptionId)
         LIMIT :limit
        """
    )
    suspend fun profilesMissingDedupeKey(subscriptionId: String, limit: Int): List<ProfileItem>

    @Query("UPDATE profiles SET dedupeKey = :key WHERE guid = :guid")
    suspend fun setDedupeKey(guid: String, key: String)

    @Query("UPDATE profiles SET dedupeKey = ''")
    suspend fun clearAllDedupeKeys()

    /** One transaction per batch so the write lock is never held for thousands of digests. */
    @Transaction
    suspend fun backfillDedupeKeyBatch(subscriptionId: String, limit: Int): Int {
        val rows = profilesMissingDedupeKey(subscriptionId, limit)
        rows.forEach { setDedupeKey(it.guid, it.computeDedupeKey()) }
        return rows.size
    }

    suspend fun backfillDedupeKeys(subscriptionId: String, batch: Int = DEDUPE_BATCH): Int {
        var total = 0
        while (true) {
            val done = backfillDedupeKeyBatch(subscriptionId, batch)
            total += done
            if (done < batch) return total
        }
    }

    // ---- Test results ----

    @Upsert
    suspend fun upsertStats(stats: List<ServerAffiliationInfo>)

    @Query("SELECT * FROM profile_stats WHERE guid = :guid")
    suspend fun stats(guid: String): ServerAffiliationInfo?

    @Query("UPDATE profile_stats SET testDelayMillis = 0 WHERE guid IN (:guids)")
    suspend fun clearDelays(guids: List<String>)

    // ---- Raw config (CUSTOM) ----

    @Query("SELECT content FROM profile_raw WHERE guid = :guid")
    suspend fun raw(guid: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRaw(raw: ProfileRaw)

    // ---- Orphan cleanup (replaces OrphanProfileCleaner / StoredProfileReference) ----

    @Query("DELETE FROM profile_stats WHERE guid NOT IN (SELECT guid FROM profiles)")
    suspend fun deleteOrphanStats(): Int

    @Query("DELETE FROM profile_raw WHERE guid NOT IN (SELECT guid FROM profiles)")
    suspend fun deleteOrphanRaws(): Int

    @Transaction
    suspend fun cleanupOrphans(): Int = deleteOrphanStats() + deleteOrphanRaws()

    /**
     * Batch import (subscription update / paste import). Replaces saveServerProfiles +
     * ProfileReplacement + OrphanProfileCleaner.
     *
     * When append is false the group's old rows are dropped first. If the selected profile is
     * among them and the incoming set carries an identical configuration, the selection is
     * repointed inside the same transaction and that guid is returned so the repository can
     * poke its snapshot.
     *
     * Replacement matching compares duplicateIdentity() objects directly instead of hashing
     * every incoming profile: both sides are already in memory, so SHA-256 would be pure cost.
     *
     * renormalize() at the end keeps sortOrder positive and dense; repeated appends would
     * otherwise drift and make the table hard to read while debugging.
     */
    @Transaction
    suspend fun replaceGroup(
        subscriptionId: String,
        profiles: List<ProfileItem>,
        raws: List<ProfileRaw>,
        append: Boolean,
    ): String? {
        var replacement: String? = null
        val selected = selectedGuid()

        if (!append) {
            val old = guidsInGroup(subscriptionId)
            if (selected != null && selected in old) {
                val identity = findByGuid(selected)?.duplicateIdentity()
                replacement = profiles.firstOrNull { identity != null && it.duplicateIdentity() == identity }?.guid
            }
            old.chunked(SQLITE_VAR_LIMIT).forEach {
                deleteProfileRows(it)
                deleteStatRows(it)
                deleteRawRows(it)
            }
        }

        val base = if (append) maxSortOrder(subscriptionId) else 0L
        upsertAll(
            profiles.mapIndexed { index, profile ->
                profile.copy(
                    subscriptionId = subscriptionId,
                    sortOrder = base + (index + 1) * ProfileItem.SORT_STEP,
                    dedupeKey = "",
                )
            }
        )
        raws.forEach { putRaw(it) }
        renormalize(subscriptionId)

        if (replacement != null) {
            writeSelectedGuid(replacement)
        } else if (selected != null && findByGuid(selected) == null) {
            writeSelectedGuid(guidsInGroup(subscriptionId).firstOrNull() ?: allGuidsInOrder().firstOrNull())
        }
        return replacement
    }

    companion object {
        /** SQLITE_MAX_VARIABLE_NUMBER defaults to 999; leave headroom. */
        const val SQLITE_VAR_LIMIT = 900
        const val DEDUPE_BATCH = 300
    }
}

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
    suspend fun deleteProfilesOf(subscriptionId: String)

    @Query("DELETE FROM profile_stats WHERE guid IN (:guids)")
    suspend fun deleteStatsOf(guids: List<String>)

    @Query("DELETE FROM profile_raw WHERE guid IN (:guids)")
    suspend fun deleteRawsOf(guids: List<String>)

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
    @Query(
        """
        SELECT p.guid FROM profiles AS p
          LEFT JOIN subscriptions AS s ON s.guid = p.subscriptionId
         ORDER BY IFNULL(s.sortOrder, 9223372036854775807), p.sortOrder, p.guid
         LIMIT 1
        """
    )
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
    suspend fun ensureDefault(defaultRemarks: String): DefaultGroupRepair {
        val created = find(AppConfig.DEFAULT_SUBSCRIPTION_ID) == null
        if (created) {
            val head = if (count() == 0) {
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
        }
        val adopted = orphanProfileCount()
        if (adopted > 0) adoptOrphanProfiles(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        return DefaultGroupRepair(createdDefault = created, adoptedProfiles = adopted)
    }

    @Transaction
    suspend fun removeWithDefault(guid: String, defaultRemarks: String) {
        val guids = profileGuidsOf(guid)
        val hadSelection = selectedGuid() in guids
        guids.chunked(ProfileDao.SQLITE_VAR_LIMIT).forEach {
            deleteStatsOf(it)
            deleteRawsOf(it)
        }
        deleteProfilesOf(guid)
        deleteRow(guid)

        ensureDefault(defaultRemarks)

        if (hadSelection) {
            val fallback = firstProfileGuid()
            if (fallback.isNullOrBlank()) clearSelectedGuid() else putSelectedGuid(fallback)
        }
    }
}

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

    /** Reset to presets. Rules with locked == true survive, matching resetRoutingRulesetsCommon. */
    @Transaction
    suspend fun replaceAll(rules: List<RulesetItem>) {
        deleteUnlocked()
        val base = maxSortOrder()
        upsertAll(
            rules.mapIndexed { index, rule ->
                rule.copy(sortOrder = base + (index + 1) * ProfileItem.SORT_STEP)
            }
        )
    }
}

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
