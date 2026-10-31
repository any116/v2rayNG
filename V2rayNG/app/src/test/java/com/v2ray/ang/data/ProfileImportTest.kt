package com.v2ray.ang.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.ProfileImportRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

            assertEquals(2, dao.importGroupBatches("subA", batches, append = false, profileCount = 2))
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
                dao.importGroupBatches("subA", (v2rayn + normal).chunked(1), append = false, profileCount = 2)
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
    fun freshBatchRejectsGuidCollisionWithoutOverwritingExistingProfile() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            dao.upsert(profile("collision").apply { password = "old" })

            var failure: Exception? = null
            try {
                dao.insertFreshAll(
                    listOf(
                        profile("collision").apply { password = "replacement" },
                        profile("fresh"),
                    )
                )
            } catch (error: Exception) {
                failure = error
            }

            assertNotNull(failure)
            assertEquals("old", dao.findByGuid("collision")!!.password)
            assertNull(dao.findMetadataByGuid("fresh"))
            assertNull(dao.findPayloadByGuid("fresh"))
        } finally {
            db.close()
        }
    }

    @Test
    fun freshBatchClearsCallerDedupeKey() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            dao.insertFreshAll(listOf(profile("fresh").apply { dedupeKey = "caller-key" }))

            assertEquals("", dao.findPayloadByGuid("fresh")!!.dedupeKey)
        } finally {
            db.close()
        }
    }

    @Test
    fun prefetchedBatchesKeepImportAtomic() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA"))
            val batches = Channel<List<ProfileImportRecord>>(2)
            batches.send(listOf(ProfileImportRecord(profile("new"))))
            batches.close()

            assertEquals(1, dao.importGroupBatchesPrefetched("subA", batches, append = false, profileCount = 1))
            assertEquals(listOf("new"), dao.guidsInGroup("subA").map { dao.findByGuid(it)!!.remarks })
        } finally {
            db.close()
        }
    }

    @Test
    fun prefetchedCancellationRollsBackReplacement() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA"))
            dao.writeSelectedGuid("old")
            val batches = Channel<List<ProfileImportRecord>>(2)
            batches.send(listOf(ProfileImportRecord(profile("new"))))
            batches.close(CancellationException("cancelled while prefetching"))

            try {
                dao.importGroupBatchesPrefetched("subA", batches, append = false, profileCount = 1)
                error("Expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(listOf("old"), dao.guidsInGroup("subA").map { dao.findByGuid(it)!!.remarks })
                assertEquals("old", dao.selectedGuid())
            }
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

            assertEquals(
                0,
                dao.importGroupBatches("subA", emptySequence(), append = false, profileCount = 0)
            )
            assertEquals("old raw", dao.raw("old"))
            assertEquals(123L, dao.stats("old")!!.testDelayMillis)
        } finally {
            db.close()
        }
    }

    @Test
    fun appendBatchesPrependInSourceOrderWithoutChangingExistingData() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            val old = profile("old", subscriptionId = "subA").apply { sortOrder = 1024L }
            dao.upsert(old)
            dao.putRaw(ProfileRaw("old", "old raw"))
            dao.upsertStats(listOf(ServerAffiliationInfo("old", testDelayMillis = 123L)))
            dao.writeSelectedGuid("old")
            val oldPayload = dao.findPayloadByGuid("old")
            val batches = sequenceOf(
                listOf(ProfileImportRecord(profile("n1"), "new raw")),
                listOf(ProfileImportRecord(profile("n2")))
            ).constrainOnce()

            assertEquals(2, dao.importGroupBatches("subA", batches, append = true, profileCount = 2))
            val imported = dao.guidsInGroup("subA").map { dao.findByGuid(it)!! }
            assertEquals(listOf("n1", "n2", "old"), imported.map { it.remarks })
            assertEquals(listOf(-1024L, 0L, 1024L), imported.map { it.sortOrder })
            assertEquals(oldPayload, dao.findPayloadByGuid("old"))
            assertEquals("old", dao.selectedGuid())
            assertEquals("old raw", dao.raw("old"))
            assertEquals(123L, dao.stats("old")!!.testDelayMillis)
            assertEquals("new raw", dao.raw(imported[0].guid))
        } finally {
            db.close()
        }
    }

    @Test
    fun largeImportReservesOneHeadRangeAcrossAllBatches() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA").apply { sortOrder = -2048L })
            val count = AppConfig.IMPORT_BATCH_SIZE * 2 + 1
            val names = (1..count).map { "n$it" }
            val batches = names.asSequence()
                .map { ProfileImportRecord(profile(it)) }
                .chunked(AppConfig.IMPORT_BATCH_SIZE)
                .constrainOnce()

            assertEquals(count, dao.importGroupBatches("subA", batches, append = true, profileCount = count))
            val rows = dao.guidsInGroup("subA").map { dao.findMetadataByGuid(it)!! }
            assertEquals(names + "old", rows.map { it.remarks })
            assertEquals(-2048L - count.toLong() * ProfileItem.SORT_STEP, rows.first().sortOrder)
            assertEquals(-2048L, rows.last().sortOrder)
            rows.zipWithNext().forEach { (before, after) ->
                assertEquals(ProfileItem.SORT_STEP, after.sortOrder - before.sortOrder)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun consecutivePrefetchedImportsPrependEvenWithEmptyBatches() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            val groupId = AppConfig.DEFAULT_SUBSCRIPTION_ID
            db.subscriptionDao().upsert(subscription(groupId))
            dao.upsert(profile("old").apply { sortOrder = -2048L })
            for (names in listOf(listOf("n1"), listOf("n2", "n3"))) {
                val batches = Channel<List<ProfileImportRecord>>(4)
                batches.send(emptyList())
                for (name in names) batches.send(listOf(ProfileImportRecord(profile(name))))
                batches.send(emptyList())
                batches.close()

                assertEquals(
                    names.size,
                    dao.importGroupBatchesPrefetched(groupId, batches, append = true, profileCount = names.size)
                )
            }

            val rows = dao.guidsInGroup(groupId).map { dao.findMetadataByGuid(it)!! }
            assertEquals(listOf("n2", "n3", "n1", "old"), rows.map { it.remarks })
            assertEquals(listOf(-5120L, -4096L, -3072L, -2048L), rows.map { it.sortOrder })
        } finally {
            db.close()
        }
    }

    @Test
    fun emptyAppendDoesNotRenormalizeOrChangeSelection() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA").apply { sortOrder = Long.MIN_VALUE })
            dao.writeSelectedGuid("old")
            val batches = Channel<List<ProfileImportRecord>>(1)
            batches.send(emptyList())
            batches.close()

            assertEquals(0, dao.importGroupBatchesPrefetched("subA", batches, append = true, profileCount = 0))
            assertEquals(Long.MIN_VALUE, dao.findMetadataByGuid("old")!!.sortOrder)
            assertEquals("old", dao.selectedGuid())
        } finally {
            db.close()
        }
    }

    @Test
    fun prependRenormalizesBeforeSortOrderWouldUnderflow() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsertAll(
                listOf(
                    profile("old1", subscriptionId = "subA").apply { sortOrder = Long.MIN_VALUE },
                    profile("old2", subscriptionId = "subA").apply { sortOrder = Long.MIN_VALUE + 1024L }
                )
            )
            val batches = sequenceOf(listOf(ProfileImportRecord(profile("n1")), ProfileImportRecord(profile("n2"))))

            assertEquals(2, dao.importGroupBatches("subA", batches, append = true, profileCount = 2))
            val rows = dao.guidsInGroup("subA").map { dao.findMetadataByGuid(it)!! }
            assertEquals(listOf("n1", "n2", "old1", "old2"), rows.map { it.remarks })
            assertEquals(listOf(-1024L, 0L, 1024L, 2048L), rows.map { it.sortOrder })
        } finally {
            db.close()
        }
    }

    @Test
    fun cancelledPrependRollsBackInsertedRowsAndRenormalization() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA").apply { sortOrder = Long.MIN_VALUE })
            dao.putRaw(ProfileRaw("old", "old raw"))
            dao.upsertStats(listOf(ServerAffiliationInfo("old", testDelayMillis = 123L)))
            dao.writeSelectedGuid("old")
            val batches = Channel<List<ProfileImportRecord>>(1)
            batches.send(listOf(ProfileImportRecord(profile("new"), "new raw")))
            batches.close(CancellationException("cancelled while prefetching"))

            try {
                dao.importGroupBatchesPrefetched("subA", batches, append = true, profileCount = 2)
                error("Expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(listOf("old"), dao.guidsInGroup("subA"))
                assertEquals(Long.MIN_VALUE, dao.findMetadataByGuid("old")!!.sortOrder)
                assertEquals("old raw", dao.raw("old"))
                assertEquals(123L, dao.stats("old")!!.testDelayMillis)
                assertEquals("old", dao.selectedGuid())
                assertEquals(0, dao.deleteOrphanPayloads())
                assertEquals(0, dao.deleteOrphanRaws())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun incorrectStagedCountRollsBackPrepend() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA"))
            dao.upsert(profile("old", subscriptionId = "subA"))
            for (count in listOf(1, 3)) {
                val batches = sequenceOf(
                    listOf(ProfileImportRecord(profile("n1"))),
                    listOf(ProfileImportRecord(profile("n2")))
                )
                var failure: IllegalStateException? = null
                try {
                    dao.importGroupBatches("subA", batches, append = true, profileCount = count)
                } catch (error: IllegalStateException) {
                    failure = error
                }
                assertNotNull(failure)
                assertEquals(listOf("old"), dao.guidsInGroup("subA"))
                assertEquals(0L, dao.findMetadataByGuid("old")!!.sortOrder)
            }
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
                dao.importGroupBatches("subA", batches, append = false, profileCount = 2)
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
                dao.importGroupBatches("subA", batches, append = false, profileCount = 2)
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
            for (append in listOf(false, true)) {
                val batches = sequenceOf(listOf(ProfileImportRecord(profile("new"))))
                assertEquals(
                    0,
                    dao.importGroupBatches("deleted-sub", batches, append = append, profileCount = 1)
                )
                assertNull(dao.findByRemarks("new"))
            }
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
