package com.v2ray.ang.ui.apppicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Stable, narrow subscriptions used by the picker top bar and list. */
@Stable
internal class AppPickerStateHolders(
    private val titleRes: State<Int>,
    private val isLoading: State<Boolean>,
    private val searchActive: () -> Boolean,
    private val apps: State<List<AppRow>>,
    private val selected: State<Set<String>>,
    private val emptyResult: State<Boolean>
) {
    val titleResProvider: () -> Int = { titleRes.value }
    val loadingProvider: () -> Boolean = { isLoading.value }
    val searchActiveProvider: () -> Boolean = searchActive
    val appsProvider: () -> List<AppRow> = { apps.value }
    val selectedProvider: () -> Set<String> = { selected.value }
    val emptyResultProvider: () -> Boolean = { emptyResult.value }
}

@Composable
internal fun rememberAppPickerStateHolders(
    viewModel: AppPickerViewModel,
    searchActive: () -> Boolean
): AppPickerStateHolders {
    val loading = viewModel.isLoading.collectAsStateWithLifecycle()
    val title = remember(viewModel) {
        viewModel.uiState.map { it.titleRes }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value.titleRes)
    val apps = remember(viewModel) {
        viewModel.uiState.map { it.apps }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value.apps)
    val selected = remember(viewModel) {
        viewModel.uiState.map { it.selected }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value.selected)
    val emptyResult = remember(viewModel) {
        viewModel.uiState.map { it.isEmptyResult }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value.isEmptyResult)

    return remember(viewModel, loading, title, apps, selected, emptyResult, searchActive) {
        AppPickerStateHolders(title, loading, searchActive, apps, selected, emptyResult)
    }
}
