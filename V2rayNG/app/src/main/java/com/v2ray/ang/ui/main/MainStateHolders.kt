package com.v2ray.ang.ui.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.paging.PagingData
import com.v2ray.ang.dto.ServerRowItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Stable
class MainSlices(
    val servers: (String) -> Flow<PagingData<ServerRowItem>>,
    val counts: (String) -> StateFlow<Int>,
)

/**
 * Saves independent list/grid viewports by group ID. Restored, unvisited groups and pending anchors
 * survive repeated saves; no Compose objects, paging rows or database payloads go into SavedState.
 */
@Stable
class GroupScrollStates private constructor(
    listSeeds: Map<String, ScrollAnchor>,
    gridSeeds: Map<String, ScrollAnchor>
) {
    constructor() : this(emptyMap(), emptyMap())

    private val listAnchors = ScrollAnchorStore(listSeeds)
    private val gridAnchors = ScrollAnchorStore(gridSeeds)
    private val listStates = HashMap<String, LazyListState>()
    private val gridStates = HashMap<String, LazyGridState>()
    private val anchorLookups = HashMap<String, CompletableDeferred<Int?>>()

    fun list(groupId: String): LazyListState = listStates.getOrPut(groupId) {
        val anchor = listAnchors.pending(groupId)
        LazyListState(anchor?.index ?: 0, anchor?.offset ?: 0)
    }

    fun grid(groupId: String): LazyGridState = gridStates.getOrPut(groupId) {
        val anchor = gridAnchors.pending(groupId)
        LazyGridState(anchor?.index ?: 0, anchor?.offset ?: 0)
    }

    private fun anchors(isGrid: Boolean): ScrollAnchorStore = if (isGrid) gridAnchors else listAnchors

    internal fun pendingAnchor(groupId: String, isGrid: Boolean): ScrollAnchor? = anchors(isGrid).pending(groupId)

    internal fun recordAnchor(groupId: String, isGrid: Boolean, anchor: ScrollAnchor) =
        anchors(isGrid).record(groupId, anchor)

    internal fun finishRestore(groupId: String, isGrid: Boolean, anchor: ScrollAnchor? = null) =
        anchors(isGrid).finishRestore(groupId, anchor)

    internal fun beginAnchorLookup(requestId: String): CompletableDeferred<Int?> =
        CompletableDeferred<Int?>().also { anchorLookups[requestId] = it }

    /** Late responses from disposed pages are ignored, rather than moving another group's viewport. */
    fun resolveAnchorIndex(requestId: String, index: Int?) {
        anchorLookups.remove(requestId)?.complete(index)
    }

    internal fun cancelAnchorLookup(requestId: String) {
        anchorLookups.remove(requestId)?.cancel()
    }

    fun retain(validIds: Set<String>) {
        listStates.keys.retainAll(validIds)
        gridStates.keys.retainAll(validIds)
        listAnchors.retain(validIds)
        gridAnchors.retain(validIds)
    }

    private fun snapshotList(): Map<String, ScrollAnchor> = listAnchors.snapshot(
        listStates.filterValues { it.layoutInfo.totalItemsCount > 0 }.mapValues { (_, state) ->
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }
    )

    private fun snapshotGrid(): Map<String, ScrollAnchor> = gridAnchors.snapshot(
        gridStates.filterValues { it.layoutInfo.totalItemsCount > 0 }.mapValues { (_, state) ->
            state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }
    )

    companion object {
        private const val SavedStateVersion = 1

        val Saver: Saver<GroupScrollStates, Any> = Saver(
            save = { holder ->
                arrayListOf(
                    SavedStateVersion,
                    encodeAnchors(holder.snapshotList()),
                    encodeAnchors(holder.snapshotGrid())
                )
            },
            restore = { saved ->
                val parts = saved as? List<*>
                if (parts != null && parts.getOrNull(0) == SavedStateVersion) {
                    GroupScrollStates(decodeAnchors(parts.getOrNull(1)), decodeAnchors(parts.getOrNull(2)))
                } else {
                    GroupScrollStates()
                }
            }
        )
    }
}

@Stable
class MainDialogHost(private val onAction: (MainAction) -> Unit) {
    var current by mutableStateOf<MainDialog?>(null)
        private set
    var confirmRemove by mutableStateOf(false)

    val show: (MainDialog) -> Unit = { current = it }
    val dismiss: () -> Unit = { current = null }
    val requestRemove: (String) -> Unit = { guid ->
        if (confirmRemove) current = MainDialog.DeleteOne(guid)
        else onAction(MainAction.RemoveServer(guid))
    }
}

@Stable
class MainScreenHandles(
    val dispatch: (MainAction) -> Unit,
    val slices: MainSlices,
    val scrollStates: GroupScrollStates,
    val showDialog: (MainDialog) -> Unit,
    val requestRemove: (String) -> Unit,
)
