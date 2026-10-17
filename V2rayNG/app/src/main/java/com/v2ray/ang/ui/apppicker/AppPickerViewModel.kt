package com.v2ray.ang.ui.apppicker

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.extension.delay
import com.v2ray.ang.data.repository.AppListRepository
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

@HiltViewModel
class AppPickerViewModel @Inject constructor(
    private val repo: AppListRepository,
    private val handle: SavedStateHandle
) : BaseViewModel<AppPickerUiState, AppPickerAction>(initialState(handle)) {

    /** Unfiltered list; the state only ever exposes the filtered view of it. */
    private var allApps: List<AppInfo> = emptyList()

    /**
     * Taken from the Intent before [restoreSavedState] runs, hence the entry state to diff the
     * result against. Declared above `init` on purpose: property initializers run first.
     */
    private val initialSelected: Set<String> = state.selected

    /** Held so a new keystroke cancels the pending filter instead of queueing another one. */
    private var queryJob: Job? = null

    init {
        restoreSavedState()
        handle.setSavedStateProvider(KEY_SAVED) {
            Bundle().apply {
                putStringArrayList(KEY_SELECTED, ArrayList(state.selected))
            }
        }
        load()
    }

    override fun onAction(action: AppPickerAction) {
        when (action) {
            AppPickerAction.Back -> back()

            is AppPickerAction.ToggleApp -> toggle(action.packageName)

            AppPickerAction.SearchOpen -> setState { copy(searchActive = true) }
            AppPickerAction.SearchClose -> closeSearch()
            is AppPickerAction.QueryChanged -> changeQuery(action.value)

            AppPickerAction.SelectAll -> mutateSelection(repo::selectAll)
            AppPickerAction.InvertSelection -> mutateSelection(repo::invert)
        }
    }

    // ===== loading =====

    private fun load() = launch(loading = true, context = Dispatchers.Default) {
        val snapshot = state.selected
        allApps = repo.loadApps(selectedSnapshot = snapshot)
        republish()
    }

    private fun restoreSavedState() {
        val saved = handle.get<Bundle>(KEY_SAVED) ?: return
        val selected = saved.getStringArrayList(KEY_SELECTED)?.toSet() ?: return
        setState { copy(selected = selected) }
    }

    // ===== search =====

    private fun changeQuery(query: String) {
        if (query == state.query) return
        applyQuery(query, searchActive = true, debounce = query.isNotEmpty())
    }

    private fun applyQuery(query: String, searchActive: Boolean, debounce: Boolean) {
        setState { copy(query = query, searchActive = searchActive) }
        queryJob?.cancel()
        queryJob = launch(context = Dispatchers.Default) {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val filtered = repo.filter(allApps, query)
            currentCoroutineContext().ensureActive()
            publishRows(filtered)
        }
    }

    private fun closeSearch() = applyQuery("", searchActive = false, debounce = false)

    private fun republish() {
        queryJob?.cancel()
        publishRows(repo.filter(allApps, state.query))
    }

    // ===== selection =====

    private fun toggle(packageName: String) {
        val current = state.selected
        val next = if (packageName in current) current - packageName else current + packageName
        setState { copy(selected = next) }
    }

    private fun mutateSelection(operation: (Set<String>, Collection<String>) -> Set<String>) {
        val visible = state.apps.map { it.packageName }
        if (visible.isEmpty()) return
        val next = operation(state.selected, visible)
        setState { copy(selected = next) }
    }

    // ===== exit =====

    private fun back() {
        if (state.searchActive) {
            closeSearch()
            return
        }
        finishWith(
            if (state.selected == initialSelected) BaseResult.Cancelled
            else BaseResult.Selected(state.selected.sorted())
        )
    }

    // ===== reduction helpers =====

    private fun publishRows(apps: List<AppInfo>) {
        val rows = apps.map { it.toRow() }
        setState { copy(apps = rows) }
    }

    private fun AppInfo.toRow() = AppRow(
        packageName = packageName,
        appName = appName,
        isUnidentified = packageName == AppConfig.UNIDENTIFIED_PACKAGE
    )

    override fun onCleared() {
        queryJob?.cancel()
        queryJob = null
        allApps = emptyList()
        super.onCleared()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val KEY_SAVED = "app_picker_saved_state"
        const val KEY_SELECTED = "selected"
    }
}

/**
 * Seeds the state from the Intent arguments.
 */
private fun initialState(handle: SavedStateHandle) = AppPickerUiState(
    titleRes = handle.get<Int>(AppRoute.EXTRA_PICKER_TITLE_RES)?.takeIf { it != 0 }
        ?: AppPickerUiState.DEFAULT_TITLE_RES,
    selected = handle.get<ArrayList<String>>(AppRoute.EXTRA_PICKER_SELECTED)?.toSet().orEmpty()
)
