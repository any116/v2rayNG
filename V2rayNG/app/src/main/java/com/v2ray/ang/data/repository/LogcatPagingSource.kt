package com.v2ray.ang.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.v2ray.ang.dto.LogcatRecord
import kotlinx.coroutines.flow.Flow

/** One page session per ViewModel; logd is read only on refresh, never on scrolling. */
class LogcatPager {
    private val lock = Any()
    private var records: List<LogcatRecord> = emptyList()
    private var source: LogcatPagingSource? = null

    val flow: Flow<PagingData<LogcatRecord>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            prefetchDistance = PREFETCH_DISTANCE,
            enablePlaceholders = true,
            jumpThreshold = JUMP_THRESHOLD
        ),
        pagingSourceFactory = {
            synchronized(lock) {
                LogcatPagingSource(records, ::snapshot).also { source = it }
            }
        }
    ).flow

    /** Invalidate the existing Pager so Paging can transfer its anchor to the new snapshot. */
    fun submit(rows: List<LogcatRecord>) {
        val invalidated = synchronized(lock) {
            if (records == rows) return
            records = rows.toList()
            source.also { source = null }
        }
        invalidated?.invalidate()
    }

    /** Full filtered snapshot, including records whose pages have not been loaded by the UI. */
    fun snapshot(): List<LogcatRecord> = synchronized(lock) { records }

    private companion object {
        const val PAGE_SIZE = 40
        const val INITIAL_LOAD_SIZE = 80
        const val PREFETCH_DISTANCE = 20
        const val JUMP_THRESHOLD = 240
    }
}

internal class LogcatPagingSource(
    private val records: List<LogcatRecord>,
    private val currentRecords: () -> List<LogcatRecord>
) : PagingSource<Int, LogcatRecord>() {

    override val jumpingSupported: Boolean = true

    override fun getRefreshKey(state: PagingState<Int, LogcatRecord>): Int? {
        val anchor = state.anchorPosition ?: return null
        // A placeholder jump has no loaded identity: do not anchor it to the nearest loaded edge.
        val id = state.pages.firstNotNullOfOrNull { page -> page.data.getOrNull(anchor - page.itemsBefore)?.id }
        val latest = currentRecords()
        val matched = latest.indexOfFirst { it.id == id }
        val position = if (matched >= 0) matched else anchor.coerceIn(0, latest.lastIndex.coerceAtLeast(0))
        return (position - state.config.initialLoadSize / 2).coerceAtLeast(0)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LogcatRecord> {
        val key = (params.key ?: 0).coerceIn(0, records.size)
        val start = if (params is LoadParams.Prepend) (key - params.loadSize).coerceAtLeast(0) else key
        val end = if (params is LoadParams.Prepend) key else (start + params.loadSize).coerceAtMost(records.size)
        return LoadResult.Page(
            data = records.subList(start, end),
            prevKey = start.takeIf { it > 0 },
            nextKey = end.takeIf { it < records.size },
            itemsBefore = start,
            itemsAfter = records.size - end
        )
    }
}
