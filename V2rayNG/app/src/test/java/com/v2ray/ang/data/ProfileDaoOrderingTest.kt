package com.v2ray.ang.data

import com.v2ray.ang.data.entities.ServerAffiliationInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private companion object {
        const val SUB = "sub"
    }
}
