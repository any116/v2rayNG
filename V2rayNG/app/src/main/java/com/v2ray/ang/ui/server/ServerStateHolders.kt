package com.v2ray.ang.ui.server

import androidx.compose.runtime.Stable
import androidx.paging.PagingData
import com.v2ray.ang.ui.compose.DropdownOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Stable
class ServerSlices(
    val chainCandidates: Flow<PagingData<DropdownOption>>,
    val fallbackTags: Flow<PagingData<DropdownOption>>,
    val chainQuery: StateFlow<String>,
    val tagQuery: StateFlow<String>,
    val onChainQueryChange: (String) -> Unit,
    val onTagQueryChange: (String) -> Unit,
)
