package com.v2ray.ang.data.repository

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.testing.asSnapshot
import com.v2ray.ang.dto.LogcatRecord
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatPagingSourceTest {

    @Test
    fun refreshAppendAndPrependCoverTheSnapshotWithoutGaps() = runTest {
        val records = records(100)
        val source = LogcatPagingSource(records) { records }
        val refresh = page(source.load(PagingSource.LoadParams.Refresh(40, 30, true)))
        val append = page(source.load(PagingSource.LoadParams.Append(refresh.nextKey!!, 40, true)))
        val prepend = page(source.load(PagingSource.LoadParams.Prepend(refresh.prevKey!!, 40, true)))

        assertEquals(records, prepend.data + refresh.data + append.data)
        assertEquals(40, refresh.itemsBefore)
        assertEquals(30, refresh.itemsAfter)
        assertEquals(null, prepend.prevKey)
        assertEquals(null, append.nextKey)
    }

    @Test
    fun refreshFollowsTheSameRecordAfterNewLogsArePrepended() = runTest {
        val records = records(100)
        var latest = records
        val source = LogcatPagingSource(records) { latest }
        val loaded = page(source.load(PagingSource.LoadParams.Refresh(40, 40, true)))
        val state = PagingState(listOf(loaded), 54, PagingConfig(40, initialLoadSize = 40), 40)
        latest = listOf(LogcatRecord(101, "new")) + records

        assertEquals(35, source.getRefreshKey(state))
        assertTrue(source.jumpingSupported)
    }

    @Test
    fun aJumpIntoUnloadedPlaceholdersUsesTheRequestedPosition() = runTest {
        val records = records(500)
        val source = LogcatPagingSource(records) { records }
        val loaded = page(source.load(PagingSource.LoadParams.Refresh(null, 80, true)))
        val state = PagingState(listOf(loaded), 350, PagingConfig(40, initialLoadSize = 80), 0)

        assertEquals(310, source.getRefreshKey(state))
    }

    @Test
    fun aClearedSnapshotLoadsAnEmptyPageEvenWithAnOldAnchor() = runTest {
        val source = LogcatPagingSource(emptyList()) { emptyList() }
        val loaded = page(source.load(PagingSource.LoadParams.Refresh(100, 80, true)))

        assertTrue(loaded.data.isEmpty())
        assertEquals(0, loaded.itemsBefore)
        assertEquals(0, loaded.itemsAfter)
        assertEquals(null, loaded.prevKey)
        assertEquals(null, loaded.nextKey)
    }

    @Test
    fun scrollingLoadsRecordsBeyondTheInitialPage() = runTest {
        val records = records(200)
        val pager = LogcatPager()
        pager.submit(records)

        val loaded = pager.flow.asSnapshot { scrollTo(records.lastIndex) }

        assertEquals(records, loaded)
    }

    private fun records(count: Int): List<LogcatRecord> =
        List(count) { LogcatRecord(it.toLong(), "log $it") }

    private fun page(result: PagingSource.LoadResult<Int, LogcatRecord>):
        PagingSource.LoadResult.Page<Int, LogcatRecord> =
        result as PagingSource.LoadResult.Page<Int, LogcatRecord>
}
