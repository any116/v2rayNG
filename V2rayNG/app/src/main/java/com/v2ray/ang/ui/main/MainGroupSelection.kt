package com.v2ray.ang.ui.main

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The selected group ID is authoritative; a restored pager index may describe an older tab list.
 * Align before observing pages, and never feed a transient list that omits the selection back into
 * persisted state. The first aligned page is restoration, not a new user selection.
 */
internal suspend fun observeGroupPagerSelection(
    groupIds: List<String>,
    selectedGroupId: String,
    settledPages: Flow<Int>,
    alignPage: suspend (Int) -> Unit,
    onSelect: (String) -> Unit
) {
    val selectedIndex = groupIds.indexOf(selectedGroupId)
    if (selectedIndex < 0) return

    alignPage(selectedIndex)
    settledPages
        .distinctUntilChanged()
        .drop(1)
        .collect { page -> groupIds.getOrNull(page)?.let(onSelect) }
}
