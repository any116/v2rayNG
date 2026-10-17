package com.v2ray.ang.ui.server

import androidx.compose.runtime.Stable
import androidx.paging.PagingData
import com.v2ray.ang.ui.components.DropdownOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Stable
class ChainMemberSlice internal constructor(
    val candidates: Flow<PagingData<DropdownOption>>,
    val query: StateFlow<String>,
    val onQueryChange: (String) -> Unit
)

@Stable
class ServerSlices(
    val fallbackTags: Flow<PagingData<DropdownOption>>,
    val tagQuery: StateFlow<String>,
    val onTagQueryChange: (String) -> Unit,
    val chainSlice: (String) -> ChainMemberSlice,
    val retainChainSlices: (Set<String>) -> Unit
)
