package com.v2ray.ang.data

import com.v2ray.ang.data.entities.ServerAffiliationInfo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the "rank while writing" bug: the previous single-statement
 * renormalize / sortByDelay re-read sortOrder through a correlated subquery while the same
 * statement rewrote it, collapsing distinct ranks to duplicates.
 */
internal class ProfileDaoOrderingTest {

    @Test
    fun renormalizeWritesDenseDistinctOrders() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            dao.upsertAll(
                listOf(
                    profile("a", subscriptionId = SUB),
                    profile("b", subscriptionId = SUB),
                    profile("c", subscriptionId = SUB),
                )
            )
            dao.setSortOrder("c", 10L)
            dao.setSortOrder("a", 20L)
            dao.setSortOrder("b", 30L)

            dao.renormalize(SUB)

            val order = dao.guidsInGroup(SUB)
            assertEquals(listOf("c", "a", "b"), order)
            assertEquals(listOf(1024L, 2048L, 3072L), order.map { dao.findByGuid(it)!!.sortOrder })
        } finally {
            db.close()
        }
    }

    @Test
    fun sortByDelayRanksFastestFirstAndFailuresLast() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            dao.upsertAll(
                listOf(
                    profile("a", subscriptionId = SUB),
                    profile("b", subscriptionId = SUB),
                    profile("c", subscriptionId = SUB),
                )
            )
            dao.upsertStats(
                listOf(
                    ServerAffiliationInfo(guid = "a", testDelayMillis = 300L),
                    ServerAffiliationInfo(guid = "b", testDelayMillis = 50L),
                    ServerAffiliationInfo(guid = "c", testDelayMillis = -1L),
                )
            )

            dao.sortByDelay(SUB)

            val order = dao.guidsInGroup(SUB)
            assertEquals(listOf("b", "a", "c"), order)
            assertEquals(listOf(1024L, 2048L, 3072L), order.map { dao.findByGuid(it)!!.sortOrder })
        } finally {
            db.close()
        }
    }

    @Test
    fun sortByDelayKeepsOrdersDistinctForEqualDelays() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            dao.upsertAll(
                listOf(
                    profile("a", subscriptionId = SUB),
                    profile("b", subscriptionId = SUB),
                    profile("c", subscriptionId = SUB),
                )
            )
            dao.upsertStats(
                listOf(
                    ServerAffiliationInfo(guid = "a", testDelayMillis = 100L),
                    ServerAffiliationInfo(guid = "b", testDelayMillis = 100L),
                    ServerAffiliationInfo(guid = "c", testDelayMillis = 100L),
                )
            )

            dao.sortByDelay(SUB)

            val orders = dao.guidsInGroup(SUB).map { dao.findByGuid(it)!!.sortOrder }
            assertEquals(setOf(1024L, 2048L, 3072L), orders.toSet())
        } finally {
            db.close()
        }
    }

    /**
     * 方案 A: a duplicated remark resolves to the row the user sees first, i.e. the one inside the
     * earlier subscription — not the one with the lower in-group index. Reordering subscriptions
     * therefore can repoint a remark reference, which is the documented behaviour.
     */
    @Test
    fun findByRemarksFollowsSubscriptionOrderThenInGroupOrder() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsertAll(
                listOf(
                    subscription("subLate").apply { sortOrder = 2048L },
                    subscription("subEarly").apply { sortOrder = 1024L },
                )
            )
            dao.upsertAll(
                listOf(
                    profile("late", remarks = "dup", subscriptionId = "subLate"),
                    profile("early", remarks = "dup", subscriptionId = "subEarly"),
                )
            )
            // The late row carries the smaller in-group index; the old `ORDER BY sortOrder, guid`
            // would have picked it.
            dao.setSortOrder("late", 1024L)
            dao.setSortOrder("early", 4096L)

            assertEquals("early", dao.findByRemarks("dup")?.guid)
        } finally {
            db.close()
        }
    }

    /** remarks() must list in the same order findByRemarks() resolves, so the dropdown agrees. */
    @Test
    fun remarksAreOrderedByGroupThenInGroupOrder() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            db.subscriptionDao().upsertAll(
                listOf(
                    subscription("subLate").apply { sortOrder = 2048L },
                    subscription("subEarly").apply { sortOrder = 1024L },
                )
            )
            dao.upsertAll(
                listOf(
                    profile("late", remarks = "z", subscriptionId = "subLate"),
                    profile("early", remarks = "a", subscriptionId = "subEarly"),
                )
            )
            // In-group order alone would rank "z" first; subscription order must win.
            dao.setSortOrder("late", 1024L)
            dao.setSortOrder("early", 4096L)

            assertEquals(listOf("a", "z"), dao.remarks(listOf(-1)))
        } finally {
            db.close()
        }
    }

    private companion object {
        const val SUB = "sub"
    }
}
