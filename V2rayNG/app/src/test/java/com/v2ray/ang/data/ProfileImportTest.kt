package com.v2ray.ang.data

import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.ProfileImportRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

internal class ProfileImportTest {

    @TempDir
    lateinit var cacheDir: File

    @Test
    fun stagedEntriesAreReadInBoundedBatchesAndDeletedAfterUse() {
        var stagedFile: File? = null
        ImportBuffer(cacheDir, ProfileImportRecord::class.java).use { buffer ->
            repeat(7) { buffer.append(ProfileImportRecord(profile("n$it"), "raw\n$it")) }
            stagedFile = cacheDir.listFiles()!!.single()
            buffer.openReader().use { reader ->
                val batches = buffer.entries(reader).chunked(3).toList()
                assertEquals(listOf(3, 3, 1), batches.map { it.size })
                assertEquals("raw\n6", batches.last().single().rawConfig)
            }
        }
        assertEquals(false, stagedFile!!.exists())
    }

    @Test
    fun replacementAcrossBatchesPreservesSelectionAndGlobalOrdering() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA").apply { sortOrder = 2048L })
            dao.upsert(profile("old", subscriptionId = "subA").apply { password = "same" })
            dao.writeSelectedGuid("old")
            val batches = sequenceOf(
                listOf(ProfileImportRecord(profile("n1").apply { password = "other" })),
                listOf(ProfileImportRecord(profile("n2").apply { password = "same" }))
            )

            assertEquals(2, dao.importGroupBatches("subA", batches, append = false))
            val imported = dao.guidsInGroup("subA").map { dao.findByGuid(it)!! }
            assertEquals(listOf("n1", "n2"), imported.map { it.remarks })
            assertEquals("n2", dao.findByGuid(dao.selectedGuid()!!)!!.remarks)
            assertEquals(2048L, imported.last().groupSortOrder)
            assertEquals(2048L, imported.last().sortOrder)
        } finally {
            db.close()
        }
    }

    @Test
    fun mergedV2raynAndNormalStagingSequencesWriteBothKinds() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            val v2rayn = sequenceOf(ProfileImportRecord(profile("v2rayn")))
            val normal = sequenceOf(ProfileImportRecord(profile("normal")))

            assertEquals(
                2,
                dao.importGroupBatches("subA", (v2rayn + normal).chunked(1), append = false)
            )
            assertEquals(
                listOf("v2rayn", "normal"),
                dao.guidsInGroup("subA").map { dao.findByGuid(it)!!.remarks }
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun emptyImportPreservesOldProfilesRawsAndStatistics() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA"))
            dao.putRaw(ProfileRaw("old", "old raw"))
            dao.upsertStats(listOf(ServerAffiliationInfo("old", testDelayMillis = 123L)))

            assertEquals(0, dao.importGroupBatches("subA", emptySequence(), append = false))
            assertEquals("old raw", dao.raw("old"))
            assertEquals(123L, dao.stats("old")!!.testDelayMillis)
        } finally {
            db.close()
        }
    }

    @Test
    fun appendBatchesContinueOrderWithoutDeletingExistingRawsOrSelection() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA").apply { sortOrder = 1024L })
            dao.putRaw(ProfileRaw("old", "old raw"))
            dao.writeSelectedGuid("old")
            val batches = sequenceOf(
                listOf(ProfileImportRecord(profile("n1"), "new raw")),
                listOf(ProfileImportRecord(profile("n2")))
            )

            assertEquals(2, dao.importGroupBatches("subA", batches, append = true))
            val imported = dao.guidsInGroup("subA").map { dao.findByGuid(it)!! }
            assertEquals(listOf("old", "n1", "n2"), imported.map { it.remarks })
            assertEquals(2048L, imported[1].sortOrder)
            assertEquals(3072L, imported[2].sortOrder)
            assertEquals("old", dao.findByGuid(dao.selectedGuid()!!)!!.remarks)
            assertEquals("old raw", dao.raw("old"))
            assertEquals("new raw", dao.raw(imported[1].guid))
        } finally {
            db.close()
        }
    }

    @Test
    fun cancellationAfterFirstBatchRollsBackReplacement() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA"))
            dao.writeSelectedGuid("old")
            val batches = sequence {
                yield(listOf(ProfileImportRecord(profile("new"))))
                throw CancellationException("cancelled while reading the next batch")
            }

            try {
                dao.importGroupBatches("subA", batches, append = false)
                error("Expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(listOf("old"), dao.guidsInGroup("subA").map { dao.findByGuid(it)!!.remarks })
                assertEquals("old", dao.findByGuid(dao.selectedGuid()!!)!!.remarks)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun failureAfterFirstBatchRollsBackDeletionAndInsertedRows() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA"))
            dao.putRaw(ProfileRaw("old", "old raw"))
            dao.upsertStats(listOf(ServerAffiliationInfo("old", testDelayMillis = 123L)))
            dao.writeSelectedGuid("old")
            val batches = sequence {
                yield(listOf(ProfileImportRecord(profile("new"), "new raw")))
                throw IllegalStateException("staging read failed")
            }

            try {
                dao.importGroupBatches("subA", batches, append = false)
                error("Expected the staging failure")
            } catch (_: IllegalStateException) {
                assertEquals(listOf("old"), dao.guidsInGroup("subA").map { dao.findByGuid(it)!!.remarks })
                assertEquals(null, dao.findByRemarks("new"))
                assertEquals("old raw", dao.raw("old"))
                assertEquals(123L, dao.stats("old")!!.testDelayMillis)
                assertEquals("old", dao.findByGuid(dao.selectedGuid()!!)!!.remarks)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun stagingIsCleanedUpWhenParsingIsCancelled() {
        assertThrows(CancellationException::class.java) {
            ImportBuffer(cacheDir, ProfileImportRecord::class.java).use { buffer ->
                buffer.append(ProfileImportRecord(profile("new")))
                throw CancellationException("cancelled")
            }
        }
        assertEquals(0, cacheDir.listFiles()!!.size)
    }

    @Test
    fun subscriptionDeletedDuringParsingDoesNotReceiveOrphanProfiles() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            val batches = sequenceOf(listOf(ProfileImportRecord(profile("new"))))

            assertEquals(0, dao.importGroupBatches("deleted-sub", batches, append = false))
            assertEquals(null, dao.findByRemarks("new"))
        } finally {
            db.close()
        }
    }

    @Test
    fun staleImportArtifactsAreRemovedButRecentArtifactsAreKept() {
        val stale = File(cacheDir, "profile-import-stale.jsonl").apply { writeText("stale") }
        val recent = File(cacheDir, "subscription-download-recent.txt").apply { writeText("recent") }
        val now = System.currentTimeMillis()
        stale.setLastModified(now - TimeUnit.HOURS.toMillis(2))

        ImportBuffer.cleanupStaleFiles(cacheDir, now)

        assertEquals(false, stale.exists())
        assertEquals(true, recent.exists())
    }
}
