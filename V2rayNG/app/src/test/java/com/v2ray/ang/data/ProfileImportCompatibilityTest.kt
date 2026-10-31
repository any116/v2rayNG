package com.v2ray.ang.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.dao.ProfileDao
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfileMetadataItem
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.ProfileImportRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Cross-operation coverage for real in-group ordering, not a display-only reversal. */
internal class ProfileImportCompatibilityTest {

    @Test
    fun importIntoEmptyDefaultGroupKeepsSourceOrderAndAllocatesFreshGuids() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            val groupId = AppConfig.DEFAULT_SUBSCRIPTION_ID
            db.subscriptionDao().upsert(subscription(groupId))
            val first = record("first")
            val second = record("second").let { it.copy(profile = it.profile.copy(guid = first.profile.guid)) }

            assertEquals(2, dao.importRecords(groupId, true, first, second))
            val rows = dao.rows(groupId)
            assertEquals(listOf("first", "second"), rows.map { it.remarks })
            assertEquals(listOf(-2048L, -1024L), rows.map { it.sortOrder })
            assertEquals(2, rows.map { it.guid }.toSet().size)
            assertTrue(rows.none { it.guid == first.profile.guid })
        } finally {
            db.close()
        }
    }

    @Test
    fun subscriptionReplacementAfterImportKeepsSourceOrderAndRepairsSelection() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription(GROUP))
            dao.upsert(record("old").profile.copy(subscriptionId = GROUP, sortOrder = 1024L))
            dao.importRecords(GROUP, true, record("discarded"), record("selected"))
            val selectedBeforeUpdate = dao.findByRemarks("selected")!!.guid
            dao.writeSelectedGuid(selectedBeforeUpdate)

            assertEquals(2, dao.importRecords(GROUP, false, record("remote"), record("selected")))
            val rows = dao.rows()
            assertEquals(listOf("remote", "selected"), rows.map { it.remarks })
            assertEquals(listOf(1024L, 2048L), rows.map { it.sortOrder })
            assertEquals(rows[1].guid, dao.selectedGuid())
            assertNotEquals(selectedBeforeUpdate, dao.selectedGuid())
            assertNull(dao.findMetadataByGuid("old"))
            assertNull(dao.findByRemarks("discarded"))
            assertNull(dao.findMetadataByGuid(selectedBeforeUpdate))
        } finally {
            db.close()
        }
    }

    @Test
    fun importAfterSubscriptionReplacementPreservesSelectionAndOtherGroups() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription(GROUP).apply { sortOrder = 2048L })
            db.subscriptionDao().upsert(subscription("other-group").apply { sortOrder = 1024L })
            dao.upsert(record("other").profile.copy(subscriptionId = "other-group", sortOrder = Long.MIN_VALUE))
            val other = dao.findMetadataByGuid("other")
            dao.importRecords(GROUP, false, record("remote-1"), record("remote-2"))
            val selected = dao.rows()[1].guid
            dao.writeSelectedGuid(selected)
            dao.upsertStats(listOf(ServerAffiliationInfo(selected, testDelayMillis = 123L)))
            val selectedPayload = dao.findPayloadByGuid(selected)

            assertEquals(2, dao.importRecords(GROUP, true, record("manual-1"), record("manual-2")))
            val rows = dao.rows()
            assertEquals(listOf("manual-1", "manual-2", "remote-1", "remote-2"), rows.map { it.remarks })
            assertEquals(listOf(-1024L, 0L, 1024L, 2048L), rows.map { it.sortOrder })
            assertEquals(listOf("other") + rows.map { it.guid }, dao.allGuidsInOrder())
            assertEquals(other, dao.findMetadataByGuid("other"))
            assertEquals(selected, dao.selectedGuid())
            assertEquals(selectedPayload, dao.findPayloadByGuid(selected))
            assertEquals(123L, dao.stats(selected)!!.testDelayMillis)
        } finally {
            db.close()
        }
    }

    @Test
    fun prependedDuplicateRemarkWinsLookupWithoutChangingSelectedGuid() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription(GROUP).apply { sortOrder = 2048L })
            dao.upsert(record("old").profile.copy(subscriptionId = GROUP, remarks = "duplicate", sortOrder = 1024L))
            dao.writeSelectedGuid("old")
            val imported = record("new").let { it.copy(profile = it.profile.copy(remarks = "duplicate")) }

            dao.importRecords(GROUP, true, imported)
            val newGuid = dao.rows().first().guid
            assertEquals(newGuid, dao.findByRemarks("duplicate")!!.guid)
            assertEquals("new", dao.findByGuid(newGuid)!!.password)
            assertEquals("old", dao.selectedGuid())
            assertEquals("old", dao.findByGuid("old")!!.password)

            // Real in-group ordering must not override the earlier subscription's precedence.
            db.subscriptionDao().upsert(subscription("earlier").apply { sortOrder = 1024L })
            dao.upsert(record("earlier").profile.copy(subscriptionId = "earlier", remarks = "duplicate"))
            assertEquals("earlier", dao.findByRemarks("duplicate")!!.guid)
        } finally {
            db.close()
        }
    }

    @Test
    fun concurrentImportsReserveSeparateRangesWithoutInterleavingBatches() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription(GROUP))
            dao.upsert(record("old").profile.copy(subscriptionId = GROUP, sortOrder = 1024L))
            dao.writeSelectedGuid("old")
            val imports = listOf(listOf("a1", "a2"), listOf("b1", "b2"), listOf("c1", "c2"))
            val start = CompletableDeferred<Unit>()
            val jobs = imports.map { names ->
                async(Dispatchers.Default) {
                    start.await()
                    dao.importRecords(GROUP, true, *names.map(::record).toTypedArray())
                }
            }
            start.complete(Unit)
            assertEquals(listOf(2, 2, 2), jobs.awaitAll())

            val rows = dao.rows()
            assertEquals(7, rows.size)
            assertEquals("old", rows.last().guid)
            assertEquals(imports.toSet(), rows.dropLast(1).map { it.remarks }.chunked(2).toSet())
            assertEquals(7, rows.map { it.guid }.toSet().size)
            rows.zipWithNext().forEach { (before, after) ->
                assertEquals(ProfileItem.SORT_STEP, after.sortOrder - before.sortOrder)
            }
            assertEquals("old", dao.selectedGuid())
        } finally {
            db.close()
        }
    }

    @Test
    fun concurrentImportAndSubscriptionReplacementHaveOnlySerialOutcomes() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription(GROUP))
            dao.upsert(record("old").profile.copy(subscriptionId = GROUP, sortOrder = 1024L))
            dao.writeSelectedGuid("old")
            val start = CompletableDeferred<Unit>()
            val imported = async(Dispatchers.Default) {
                start.await()
                dao.importRecords(GROUP, true, record("manual-1"), record("manual-2"))
            }
            val updated = async(Dispatchers.Default) {
                start.await()
                dao.importRecords(GROUP, false, record("remote-1"), record("remote-2"))
            }
            start.complete(Unit)
            assertEquals(2, imported.await())
            assertEquals(2, updated.await())

            val rows = dao.rows()
            assertTrue(
                rows.map { it.remarks } in listOf(
                    listOf("remote-1", "remote-2"),
                    listOf("manual-1", "manual-2", "remote-1", "remote-2")
                )
            )
            assertEquals("remote-1", dao.findByGuid(dao.selectedGuid()!!)!!.remarks)
            assertEquals(rows.size, rows.map { it.sortOrder }.toSet().size)
            assertNull(dao.findByGuid("old"))
            assertEquals(0, dao.deleteOrphanPayloads())
            assertEquals(0, dao.deleteOrphanRaws())
        } finally {
            db.close()
        }
    }

    @Test
    fun importsRemainCompatibleWithDragAndDelaySorting() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription(GROUP))
            dao.upsert(record("old").profile.copy(subscriptionId = GROUP, sortOrder = 1024L))
            dao.importRecords(GROUP, true, record("first"), record("fast"))
            val fastGuid = dao.findByRemarks("fast")!!.guid

            dao.moveProfileToIndex(GROUP, "", "old", 0)
            assertEquals(listOf("old", "first", "fast"), dao.rows().map { it.remarks })
            dao.importRecords(GROUP, true, record("after-drag"))
            assertEquals(listOf("after-drag", "old", "first", "fast"), dao.rows().map { it.remarks })

            dao.upsertStats(listOf(ServerAffiliationInfo(fastGuid, testDelayMillis = 10L)))
            dao.sortByDelay(GROUP)
            assertEquals(listOf("fast", "after-drag", "old", "first"), dao.rows().map { it.remarks })
            dao.importRecords(GROUP, true, record("after-sort"))
            val rows = dao.rows()
            assertEquals(listOf("after-sort", "fast", "after-drag", "old", "first"), rows.map { it.remarks })
            assertEquals(listOf(0L, 1024L, 2048L, 3072L, 4096L), rows.map { it.sortOrder })
        } finally {
            db.close()
        }
    }

    /** Distinct identities let replacement tests distinguish matching from first-row fallback. */
    private fun record(name: String): ProfileImportRecord =
        ProfileImportRecord(profile(name).apply { password = name })

    /** Exercise the production channel adapter, with each record crossing a batch boundary. */
    private suspend fun ProfileDao.importRecords(
        groupId: String,
        append: Boolean,
        vararg records: ProfileImportRecord
    ): Int {
        val batches = Channel<List<ProfileImportRecord>>(records.size.coerceAtLeast(1))
        records.forEach { batches.send(listOf(it)) }
        batches.close()
        return try {
            importGroupBatchesPrefetched(groupId, batches, append, profileCount = records.size)
        } finally {
            batches.cancel()
        }
    }

    private suspend fun ProfileDao.rows(groupId: String = GROUP): List<ProfileMetadataItem> =
        guidsInGroup(groupId).map { findMetadataByGuid(it)!! }

    private companion object {
        const val GROUP = "subA"
    }
}
