package com.v2ray.ang.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.asSnapshot
import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.normalizeLike
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equivalence and regression coverage for the scope-split queries.
 *
 * The former single statements used `(:subscriptionId = '' OR p.subscriptionId = :id)`, which
 * SQLite cannot serve from an index (the OR term `:subscriptionId = ''` is not a column
 * constraint), so every group page scanned the global order. The split variants must return
 * identical results for both scopes, with and without a search term — that is what the tests
 * below pin. Per repository-rules.md section 3, a query change needs a "search truth test"
 * first; these are it.
 */
internal class ProfileDaoQueryTest {

    // ------------------------------------------------------------------
    // Fixture: subA(g1,g2,g3) + subB(g4) + orphan(g5). g1..g3 share
    // sortOrder 0 and are ordered by the guid tiebreak; g4/g5 carry 1024.
    // Delays: g2 and g4 failed (-1). Global list order: g1,g2,g3,g4,g5.
    // ------------------------------------------------------------------

    private suspend fun seed(db: AppDatabase) {
        val dao = db.profileDao()
        db.subscriptionDao().upsertAll(
            listOf(
                subscription("subA").apply { sortOrder = 1024L },
                subscription("subB").apply { sortOrder = 2048L },
            )
        )
        dao.upsertAll(
            listOf(
                profile("g1", remarks = "alpha one", subscriptionId = "subA"),
                profile("g2", remarks = "beta two", subscriptionId = "subA"),
                profile("g3", remarks = "gamma three", subscriptionId = "subA"),
                profile("g4", remarks = "alpha four", subscriptionId = "subB"),
                profile("g5", remarks = "orphan five", subscriptionId = "orphan"),
            )
        )
        dao.setSortOrder("g4", 1024L)
        dao.setSortOrder("g5", 1024L)
        dao.upsertStats(
            listOf(
                ServerAffiliationInfo(guid = "g2", testDelayMillis = -1L),
                ServerAffiliationInfo(guid = "g4", testDelayMillis = -1L),
            )
        )
    }

    /** The DAO exposes PagingSource; wrap it like MainRepository does before snapshotting. */
    private suspend fun pageSnapshot(
        dao: ProfileDao,
        subscriptionId: String,
        query: String,
    ): List<ServerRowProjection> =
        Pager(PagingConfig(pageSize = 10, initialLoadSize = 20, enablePlaceholders = false)) {
            dao.pageServers(subscriptionId, query)
        }.flow.asSnapshot()

    // ------------------------------------------------------------------
    // pageServers
    // ------------------------------------------------------------------

    @Test
    fun pageServersSplitScopesMatchTheFormerSingleStatement() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            seed(db)

            val groupPage = pageSnapshot(dao, "subA", "")
            val allPage = pageSnapshot(dao, "", "")
            assertEquals(listOf("g1", "g2", "g3"), groupPage.map { it.guid })
            assertEquals(listOf("g1", "g2", "g3", "g4", "g5"), allPage.map { it.guid })
        } finally {
            db.close()
        }
    }

    @Test
    fun pageServersWithSearchTermFiltersBothScopes() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            seed(db)

            val groupPage = pageSnapshot(dao, "subA", "alpha".normalizeLike())
            val allPage = pageSnapshot(dao, "", "alpha".normalizeLike())
            assertEquals(listOf("g1"), groupPage.map { it.guid })
            assertEquals(listOf("g1", "g4"), allPage.map { it.guid })
        } finally {
            db.close()
        }
    }

    /** ServerRowProjection equality covers every column, including testDelayMillis and badge. */
    @Test
    fun pageGroupServersProjectionMatchesTheFormerJoinedShape() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            seed(db)

            val rows = pageSnapshot(dao, "subA", "")
            assertEquals(0L, rows.first { it.guid == "g1" }.testDelayMillis)
            assertEquals(-1L, rows.first { it.guid == "g2" }.testDelayMillis)
            // The group page renders no badge, so subscriptionInitial must be null (and the
            // CAST(NULL AS TEXT) must survive Room's column-type resolution).
            assertTrue(rows.all { it.subscriptionInitial == null })
            assertEquals("subA", rows.first().subscriptionId)
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // indexOf / guidsInScope — the other scope-split pairs.
    // ------------------------------------------------------------------

    @Test
    fun indexOfSplitScopesMatchTheFormerSingleStatement() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            seed(db)

            assertEquals(0, dao.indexOf("subA", "", "g1"))
            assertEquals(2, dao.indexOf("subA", "", "g3"))
            assertEquals(3, dao.indexOf("", "", "g4"))
            assertEquals(4, dao.indexOf("", "", "g5"))
            // Not in scope / filtered out -> null.
            assertEquals(null, dao.indexOf("subB", "", "g1"))
            assertEquals(null, dao.indexOf("subA", "alpha".normalizeLike(), "g3"))
        } finally {
            db.close()
        }
    }

    @Test
    fun guidsInScopeSplitScopesMatchTheFormerSingleStatement() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            seed(db)

            assertEquals(listOf("g1", "g2", "g3"), dao.guidsInScope("subA", ""))
            assertEquals(listOf("g4"), dao.guidsInScope("subB", ""))
            assertEquals(listOf("g1", "g2", "g3", "g4", "g5"), dao.guidsInScope("", ""))
            assertEquals(listOf("g1", "g4"), dao.guidsInScope("", "alpha".normalizeLike()))
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // duplicateGuids: window function replaces the correlated subquery.
    // Keeper rule: lowest (sortOrder, guid) of the SAME visible set.
    // ------------------------------------------------------------------

    @Test
    fun duplicateGuidsKeepsLowestSortOrderPerDedupeKey() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            seed(db)
            val complex = emptyList<Int>()

            // One identity shared by g1..g3 (subA) and g4 (subB).
            dao.setDedupeKey("g1", "k1")
            dao.setDedupeKey("g2", "k1")
            dao.setDedupeKey("g3", "k1")
            dao.setDedupeKey("g4", "k1")

            // In-group: g1 (sortOrder tie broken by guid) is the keeper.
            assertEquals(listOf("g2", "g3"), dao.duplicateGuids("subA", "", complex))
            assertEquals(listOf("g2", "g3", "g4"), dao.duplicateGuids("", "", complex))

            // A copy outside the visible set cannot be the keeper for the visible ones.
            assertEquals(listOf("g4"), dao.duplicateGuids("", "alpha".normalizeLike(), complex))
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // observeCounts: the two branches partition the table, sum == total.
    // ------------------------------------------------------------------

    @Test
    fun observeCountsSumsToTotalIncludingOrphansAndEmptyGroups() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subEmpty"))
            seed(db)

            val counts = dao.observeCounts("").first()
            assertEquals(6, counts.sumOf { it.count })
            assertEquals(4, counts.size)
            assertEquals(3, counts.first { it.groupId == "subA" }.count)
            assertEquals(0, counts.first { it.groupId == "subEmpty" }.count)
            assertEquals(1, counts.first { it.groupId == "orphan" }.count)
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // replaceGroup groupSortOrder pre-fill: the DOCUMENTED exception to
    // "never write groupSortOrder by hand" (repository-rules.md section 3).
    // GROUP_ORDER_TRIGGERS stay the source of truth; these three tests pin
    // hint and trigger agreement in both directions.
    // ------------------------------------------------------------------

    /** Existing subscription: the hint matches the trigger, so no corrective UPDATE is needed. */
    @Test
    fun replaceGroupPreFillsGroupSortOrderFromSubscription() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA").apply { sortOrder = 2048L })

            dao.replaceGroup("subA", listOf(profile("n1"), profile("n2")), emptyList(), append = false)

            assertEquals(
                listOf(2048L, 2048L),
                dao.guidsInGroup("subA").map { dao.findByGuid(it)!!.groupSortOrder },
            )
        } finally {
            db.close()
        }
    }

    /** Orphan target: the trigger writes the Long.MAX_VALUE fallback; the hint agrees. */
    @Test
    fun replaceGroupOnOrphanGroupFallsBackToLongMaxValue() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()

            dao.replaceGroup("orphan", listOf(profile("n1")), emptyList(), append = false)

            assertEquals(Long.MAX_VALUE, dao.findByGuid("n1")!!.groupSortOrder)
        } finally {
            db.close()
        }
    }

    /** A wrong hint is repaired: reordering the subscription cascades onto every row. */
    @Test
    fun groupSortOrderFollowsSubscriptionReorderAfterReplaceGroup() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            val subDao = db.subscriptionDao()
            subDao.upsert(subscription("subA").apply { sortOrder = 2048L })

            dao.replaceGroup("subA", listOf(profile("n1"), profile("n2")), emptyList(), append = false)
            assertEquals(
                listOf(2048L, 2048L),
                dao.guidsInGroup("subA").map { dao.findByGuid(it)!!.groupSortOrder },
            )

            subDao.setSortOrder("subA", 1024L)
            assertEquals(
                listOf(1024L, 1024L),
                dao.guidsInGroup("subA").map { dao.findByGuid(it)!!.groupSortOrder },
            )
        } finally {
            db.close()
        }
    }

    /** CUSTOM imports feed the @Insert(REPLACE) batch path. */
    @Test
    fun replaceGroupWritesRawsThroughTheBatchPath() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            val custom = profile("c1", type = EConfigType.CUSTOM)

            dao.replaceGroup(
                "orphan",
                listOf(custom),
                listOf(ProfileRaw(guid = "c1", content = "{}")),
                append = false,
            )

            assertEquals("{}", dao.raw("c1"))
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------
    // Selection repair on delete/replace uses the LIMIT 1 probes now.
    // ------------------------------------------------------------------

    @Test
    fun deleteProfilesRepointsSelectionToFirstRowOfTheGroup() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            seed(db)
            dao.writeSelectedGuid("g3")

            dao.deleteProfiles(listOf("g3"))

            assertEquals("g1", dao.selectedGuid())
        } finally {
            db.close()
        }
    }

    @Test
    fun replaceGroupRepointsSelectionWhenTheOldRowVanishes() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsert(subscription("subA").apply { sortOrder = 1024L })
            dao.upsert(profile("old", subscriptionId = "subA"))
            dao.writeSelectedGuid("old")

            dao.replaceGroup("subA", listOf(profile("n1", remarks = "old")), emptyList(), append = false)

            // Identical duplicateIdentity() -> selection repointed to the incoming row.
            assertEquals("n1", dao.selectedGuid())
        } finally {
            db.close()
        }
    }
}
