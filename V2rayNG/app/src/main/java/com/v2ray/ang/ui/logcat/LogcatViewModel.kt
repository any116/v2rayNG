package com.v2ray.ang.ui.logcat

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.v2ray.ang.R
import com.v2ray.ang.data.repository.LogcatRepository
import com.v2ray.ang.di.DefaultDispatcher
import com.v2ray.ang.dto.LogcatRecord
import com.v2ray.ang.extension.delay
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LogcatViewModel @Inject constructor(
    private val repo: LogcatRepository,
    @DefaultDispatcher private val cpu: CoroutineDispatcher
) : BaseViewModel<LogcatUiState, LogcatAction>(LogcatUiState()) {

    private val pager = repo.createPager()
    val lines: Flow<PagingData<LogLine>> = pager.flow.map { page ->
        page.map { record -> withContext(cpu) { parseLogLine(record) } }
    }.cachedIn(viewModelScope)

    /** Unfiltered records are authoritative; the Pager holds their current filtered projection. */
    private var snapshot: List<LogcatRecord> = emptyList()
    private var bufferJob: Job? = null
    private var filterJob: Job? = null
    private var observeJob: Job? = null
    private var clearing = false

    override fun onAction(action: LogcatAction) {
        when (action) {
            LogcatAction.Started -> startObserving()
            LogcatAction.Stopped -> stopObserving()
            LogcatAction.Back -> back()
            LogcatAction.Refresh -> refresh()
            LogcatAction.CopyAll -> copyAll()
            LogcatAction.Clear -> clear()
            LogcatAction.Share -> share()
            LogcatAction.SearchOpened -> setState { copy(searchActive = true) }
            LogcatAction.SearchClosed -> closeSearch()
            is LogcatAction.QueryChanged -> changeQuery(action.value)
            is LogcatAction.LineLongPressed -> copyLine(action.text)
            is LogcatAction.ShareFinished -> if (!action.ok) toastError()
        }
    }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = launch(onError = {}) {
            while (true) {
                currentCoroutineContext().ensureActive()
                expireSnapshot()
                refresh(userInitiated = false)
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
        if (!clearing) bufferJob?.cancel()
        filterJob?.cancel()
        snapshot = emptyList()
        pager.submit(emptyList())
    }

    private fun back() {
        if (state.searchActive) closeSearch() else finishWith(BaseResult.Cancelled)
    }

    private fun closeSearch() {
        if (!state.searchActive && state.query.isEmpty()) return
        setState { copy(searchActive = false, query = "") }
        applyFilter(debounce = false)
    }

    private fun changeQuery(value: String) {
        if (value == state.query) return
        setState { copy(query = value) }
        applyFilter(debounce = value.isNotBlank())
    }

    private fun refresh(userInitiated: Boolean = true) {
        if (clearing || bufferJob?.isActive == true) return
        bufferJob = launch(loading = userInitiated, onError = { if (userInitiated) toastError() }) {
            val raw = repo.read()
            currentCoroutineContext().ensureActive()
            val previous = snapshot
            snapshot = withContext(cpu) { repo.identify(raw, previous) }
            applyFilter(debounce = false)
        }
    }

    private fun clear() {
        if (clearing) return
        clearing = true
        val previous = bufferJob
        previous?.cancel()
        filterJob?.cancel()
        bufferJob = launch(loading = true) {
            try {
                previous?.join()
                if (!repo.clear()) return@launch toastError()
                filterJob?.cancel()
                snapshot = emptyList()
                pager.submit(emptyList())
                toastSuccess()
            } finally {
                clearing = false
            }
        }
    }

    private suspend fun expireSnapshot() {
        if (clearing || snapshot.isEmpty()) return
        val source = snapshot
        val retained = withContext(cpu) { repo.retainRecords(source) }
        if (!clearing && snapshot === source && retained != source) {
            snapshot = retained
            applyFilter(debounce = false)
        }
    }

    private fun applyFilter(debounce: Boolean) {
        filterJob?.cancel()
        filterJob = launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val query = state.query.trim()
            val source = snapshot
            val visible = withContext(cpu) {
                val retained = repo.retainRecords(source)
                if (query.isEmpty()) retained else retained.filter { it.raw.contains(query, ignoreCase = true) }
            }
            pager.submit(visible)
        }
    }

    private fun copyLine(text: String) = launch {
        val retained = withContext(cpu) { repo.retain(listOf(text)) }
        if (retained.isEmpty()) return@launch toastError(R.string.toast_none_data)
        repo.copyToClipboard(retained.single())
        toastSuccess()
    }

    private fun copyAll() = launch {
        val lines = pager.snapshot()
        val retained = withContext(cpu) { repo.retain(lines.map { it.raw }) }
        if (retained.isEmpty()) return@launch toastError(R.string.toast_none_data)
        val text = withContext(cpu) { retained.joinToString("\n") }
        repo.copyToClipboard(text)
        toastSuccess()
    }

    private fun share() = launch(loading = true) {
        val lines = pager.snapshot()
        val raw = withContext(cpu) { repo.retain(lines.map { it.raw }) }
        if (raw.isEmpty()) return@launch toastError(R.string.toast_none_data)
        val path = repo.writeShareFile(raw) ?: return@launch toastError()
        platform(LogcatEvent.ShareFile(path))
    }

    override fun onCleared() {
        stopObserving()
        bufferJob?.cancel()
        filterJob?.cancel()
        bufferJob = null
        filterJob = null
        snapshot = emptyList()
        super.onCleared()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val REFRESH_INTERVAL_MS = 10_000L
    }
}
