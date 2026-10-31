package com.v2ray.ang.ui.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.v2ray.ang.dto.ServerRowItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val AnchorRestoreTimeoutMs = 2_000L

/**
 * "" is the unfiltered SQL scope produced by MainRepository.serverPager, never a wildcard: an anchor
 * captured under another filter resolves against a different row set and must not be applied.
 */
internal fun fitsQueryScope(anchor: ScrollAnchor, requestedQuery: String): Boolean =
    anchor.query == requestedQuery

@Composable
internal fun ObserveListScrollAnchor(
    groupId: String,
    query: String,
    items: LazyPagingItems<ServerRowItem>,
    scrollStates: GroupScrollStates,
    state: LazyListState,
    onAction: (MainAction) -> Unit
) {
    ObserveGroupScrollAnchor(
        groupId, query, items, scrollStates, state, false, onAction,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        layoutReady = {
            state.layoutInfo.viewportSize.height > 0 && state.layoutInfo.totalItemsCount == items.itemCount
        },
        isScrolling = { state.isScrollInProgress },
        scrollTo = { state.scrollToItem(it.index, it.offset) }
    )
}

@Composable
internal fun ObserveGridScrollAnchor(
    groupId: String,
    query: String,
    items: LazyPagingItems<ServerRowItem>,
    scrollStates: GroupScrollStates,
    state: LazyGridState,
    onAction: (MainAction) -> Unit
) {
    ObserveGroupScrollAnchor(
        groupId, query, items, scrollStates, state, true, onAction,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        layoutReady = {
            state.layoutInfo.viewportSize.height > 0 && state.layoutInfo.totalItemsCount == items.itemCount
        },
        isScrolling = { state.isScrollInProgress },
        scrollTo = { state.scrollToItem(it.index, it.offset) }
    )
}

/**
 * Restore only a composed page and only once. SQL and presentation waits are bounded; scrolling or
 * explicit locating wins over late lookup results. Tracking never requests unloaded paging rows.
 */
@Composable
private fun ObserveGroupScrollAnchor(
    groupId: String,
    query: String,
    items: LazyPagingItems<ServerRowItem>,
    scrollStates: GroupScrollStates,
    stateKey: Any,
    isGrid: Boolean,
    onAction: (MainAction) -> Unit,
    position: () -> Pair<Int, Int>,
    layoutReady: () -> Boolean,
    isScrolling: () -> Boolean,
    scrollTo: suspend (ScrollAnchor) -> Unit
) {
    val latestAction by rememberUpdatedState(onAction)
    val requestedQuery = query.trim()
    // items is a remembered instance; internal paging updates do not restart this effect.
    LaunchedEffect(scrollStates, stateKey, groupId, isGrid, items, requestedQuery) {
        val seed = scrollStates.pendingAnchor(groupId, isGrid) ?: return@LaunchedEffect
        if (!fitsQueryScope(seed, requestedQuery)) {
            // list()/grid() may already be initialised from a seed of another scope. Drop it and start
            // this scope at the top instead of resolving an index in the wrong row set.
            val reset = ScrollAnchor(0, 0, query = requestedQuery)
            scrollStates.finishRestore(groupId, isGrid, reset)
            if (!isScrolling() && position() != (0 to 0)) scrollTo(reset)
            return@LaunchedEffect
        }
        if (isScrolling()) {
            scrollStates.finishRestore(groupId, isGrid)
            return@LaunchedEffect
        }

        var applying = false
        val restoration = launch {
            var target: ScrollAnchor? = null
            try {
                target = withTimeoutOrNull(AnchorRestoreTimeoutMs) {
                    snapshotFlow { pageSettled(items, seed.query) && layoutReady() }.first { it }
                    if (items.loadState.refresh is LoadState.Error) return@withTimeoutOrNull null
                    val sqlIndex = if (seed.guid != null && items.itemCount > 0) {
                        val requestId = UUID.randomUUID().toString()
                        val response = scrollStates.beginAnchorLookup(requestId)
                        try {
                            latestAction(MainAction.RestoreScrollAnchor(requestId, groupId, seed.guid, seed.query))
                            response.await()
                        } finally {
                            scrollStates.cancelAnchorLookup(requestId)
                            latestAction(MainAction.CancelScrollAnchorRestore(requestId))
                        }
                    } else {
                        null
                    }
                    // A refresh may have changed the count while SQL was running.
                    snapshotFlow { pageSettled(items, seed.query) && layoutReady() }.first { it }
                    if (items.loadState.refresh is LoadState.Error || isScrolling() ||
                        scrollStates.pendingAnchor(groupId, isGrid) != seed
                    ) return@withTimeoutOrNull null
                    val restored = restoredScrollAnchor(seed, sqlIndex, items.itemCount)
                    // From here the viewport belongs to this coroutine, so the scrolling observer stops
                    // cancelling it. scrollTo stays cancellable, so user input still wins.
                    applying = true
                    if (items.itemCount > 0 && position() != (restored.index to restored.offset)) scrollTo(restored)
                    restored
                }
            } finally {
                // Completion, timeout or an interrupted scroll release the seed, otherwise every later
                // record would be ignored. Leaving composition before applying keeps it for a later visit.
                if (applying || currentCoroutineContext().isActive) {
                    scrollStates.finishRestore(groupId, isGrid, target)
                }
            }
        }
        val scrolling = launch {
            snapshotFlow { isScrolling() }.first { it }
            if (!applying) {
                scrollStates.finishRestore(groupId, isGrid)
                restoration.cancel()
            }
        }
        restoration.join()
        scrolling.cancel()
    }

    // The stable items reference is a key here too; snapshotFlow observes the mutable paging state.
    LaunchedEffect(scrollStates, stateKey, groupId, isGrid, items, requestedQuery) {
        snapshotFlow {
            if (items.loadState.refresh !is LoadState.NotLoading || !layoutReady()) return@snapshotFlow null
            val snapshot = items.itemSnapshotList
            if (snapshot.size == 0) {
                if (items.loadState.append.endOfPaginationReached) ScrollAnchor(0, 0, query = requestedQuery) else null
            } else {
                // Requested text can be ahead of the currently presented/debouncing generation.
                val presentedQuery = snapshot.items.firstOrNull()?.pagingQuery ?: return@snapshotFlow null
                val (index, offset) = position()
                ScrollAnchor(index, offset, snapshot.getOrNull(index)?.guid, presentedQuery)
            }
        }.filterNotNull().collect { scrollStates.recordAnchor(groupId, isGrid, it) }
    }
}

private fun pageSettled(items: LazyPagingItems<ServerRowItem>, expectedQuery: String): Boolean {
    val states = items.loadState
    return isPageSettled(
        refresh = states.refresh,
        append = states.append,
        itemCount = items.itemCount,
        presentedQuery = items.itemSnapshotList.items.firstOrNull()?.pagingQuery,
        expectedQuery = expectedQuery
    )
}

/**
 * Pure settlement predicate so the empty-result and scope rules can be tested without a composition.
 * An empty page carries no row and therefore no [ServerRowItem.pagingQuery], so only the forward end
 * flag can prove that the local load finished. REFRESH.endOfPaginationReached is always false and
 * PREPEND may stay un-ended on the first empty load, so neither can gate this decision.
 */
internal fun isPageSettled(
    refresh: LoadState,
    append: LoadState,
    itemCount: Int,
    presentedQuery: String?,
    expectedQuery: String
): Boolean {
    if (refresh is LoadState.Error) return true
    if (refresh !is LoadState.NotLoading) return false
    if (itemCount == 0) return append is LoadState.NotLoading && append.endOfPaginationReached
    return presentedQuery == expectedQuery
}
