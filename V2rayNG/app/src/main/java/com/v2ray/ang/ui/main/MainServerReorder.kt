package com.v2ray.ang.ui.main

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private const val ServerReorderPresentationTimeoutMillis = 5_000L

/**
 * Reorderable's onMove must not return until Paging presents the moved row at its target index.
 * Dispatch alone only starts the Room write: returning earlier makes the library compensate for
 * the new position while the row is still laid out at the old one, flashing it over its neighbour.
 * Keeping onMove suspended also keeps the library's move mutex held through the paging refresh.
 *
 * A failed/stale refresh cancels the move callback rather than returning successfully with the old
 * order and applying that incorrect position compensation. The ViewModel owns the dispatched write.
 */
internal suspend fun moveServerAndAwaitPresentation(
    action: MainAction.MoveServer,
    dispatch: (MainAction) -> Unit,
    targetGuid: Flow<String?>
) {
    dispatch(action)
    withTimeout(ServerReorderPresentationTimeoutMillis) {
        targetGuid.first { it == action.movedGuid }
    }
}
