package com.v2ray.ang.ui.main

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.v2ray.ang.R
import com.v2ray.ang.data.repository.MainRepository
import com.v2ray.ang.data.repository.MainServiceEvent
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.ServerRowItem
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.ui.base.BaseText
import com.v2ray.ang.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MS = 300L
private const val COUNT_SHARING_TIMEOUT_MS = 5_000L

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repo: MainRepository,
) : BaseViewModel<MainUiState, MainAction>(
    MainUiState(
        selectedGroupId = repo.selectedGroupId(),
        selectedGuid = repo.selectedGuid(),
        confirmRemove = repo.confirmRemove(),
        doubleColumnDisplay = repo.doubleColumnDisplay(),
    )
) {

    private val query = MutableStateFlow("")
    private val debouncedQuery = query.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged()

    /**
     * One cached Pager flow per group, so swiping back and forth in HorizontalPager does not
     * rebuild the PagingSource. A new search term swaps the PagingSource, not the flow.
     */
    private val pagers = ConcurrentHashMap<String, Flow<PagingData<ServerRowItem>>>()

    fun servers(groupId: String): Flow<PagingData<ServerRowItem>> =
        pagers.computeIfAbsent(groupId) {
            debouncedQuery
                .flatMapLatest { repo.serverPager(groupId, it) }
                .cachedIn(viewModelScope)
        }

    private val counts: StateFlow<Map<String, Int>> =
        debouncedQuery
            .flatMapLatest { repo.observeCounts(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(COUNT_SHARING_TIMEOUT_MS),
                emptyMap()
            )

    private val countFlows = ConcurrentHashMap<String, StateFlow<Int>>()

    fun serverCount(groupId: String): StateFlow<Int> =
        countFlows.computeIfAbsent(groupId) {
            counts.map { it[groupId] ?: 0 }
                .distinctUntilChanged()
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(COUNT_SHARING_TIMEOUT_MS),
                    counts.value[groupId] ?: 0
                )
        }

    private var currentTestId: String? = null
    private var batchTestId: String? = null
    private var batchGroupId: String? = null

    private var initialized = false
    private val firstPageReady = CompletableDeferred<Unit>()

    init {
        observeServiceEvents()
        observeGroups()
    }

    /** Delegates to MainRepository: suspends until the settings snapshot is ready. */
    suspend fun awaitReady() = repo.awaitReady()

    override fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> repo.refreshGroups()
            MainAction.ToggleService -> if (state.isRunning) platform(MainEvent.StopService) else startCore()
            MainAction.RestartService -> restartCore()
            MainAction.StatusBarClick -> when {
                state.isTesting -> cancelTesting()
                state.isRunning -> testCurrentServer()
                else -> Unit
            }
            MainAction.TestAllServers -> testAll(onlyTcp = true)
            MainAction.TestRealAllServers -> testAll(onlyTcp = false)
            MainAction.CancelTesting -> cancelTesting()
            MainAction.RemoveAllServers -> removeAllServers()
            MainAction.RemoveDuplicateServers -> removeDuplicateServers()
            MainAction.RemoveInvalidServers -> removeInvalidServers()
            MainAction.SortByTestResults -> sortByTestResults()
            MainAction.UpdateSubscriptions -> updateSubscriptions()
            MainAction.ExportAll -> exportAll()
            MainAction.ImportFromQrCode -> platform(MainEvent.ScanQrCode)
            MainAction.ImportFromFile -> platform(MainEvent.PickConfigFile)
            MainAction.ImportFromClipboard -> launch(loading = true) { importBatchConfig(repo.readClipboard()) }
            is MainAction.ConfigFileSelected -> launch(loading = true) { importBatchConfig(repo.readTextFromUri(action.uri).orEmpty()) }
            is MainAction.ImportBatchConfig -> launch(loading = true) { importBatchConfig(action.configText) }
            is MainAction.SelectGroup -> selectGroup(action.groupId)
            is MainAction.SelectServer -> selectServer(action.guid)
            is MainAction.RemoveServer -> removeServer(action.guid)
            is MainAction.MoveServer -> moveServer(action)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.SetSearchActive -> setSearchActive(action.active)
            MainAction.LocateSelectedServer -> locateSelectedServer()
            MainAction.LocateFailed -> toastError()
            is MainAction.AddServer -> navigate(
                AppRoute.ServerEdit(
                    configType = action.configType,
                    subscriptionId = state.selectedGroupId,
                    isRunning = state.isRunning,
                )
            )
            is MainAction.EditServer -> navigate(
                AppRoute.ServerEdit(
                    configType = action.configType,
                    guid = action.guid,
                    subscriptionId = state.selectedGroupId,
                    isRunning = state.isRunning,
                )
            )
            is MainAction.ShareQrCode -> launch {
                val bitmap = repo.share2QRCode(action.guid)
                if (bitmap == null) toastError() else platform(MainEvent.ShowQrCode(bitmap))
            }
            is MainAction.ShareClipboard -> launch {
                if (repo.share2Clipboard(action.guid)) toastSuccess() else toastError()
            }
            is MainAction.ShareFullContent -> launch {
                if (repo.shareFullContent(action.guid)) toastSuccess() else toastError()
            }
            is MainAction.Navigate -> navigate(action.route)
            MainAction.OpenPromotion -> navigate(AppRoute.OpenUrl(repo.promotionUrl()))
            is MainAction.ResultReceived -> handleResult(action.result)
        }
    }

    /** Subscription-table changes push new tabs; the selection is re-resolved on every emission. */
    private fun observeGroups() = launch(onError = {}) {
        repo.awaitReady()
        repo.observeGroups().collect { groups ->
            val validIds = groups.mapTo(HashSet()) { it.id }
            pagers.keys.removeAll { it !in validIds }
            countFlows.keys.removeAll { it !in validIds }
            val selected = resolveSelectedGroup(groups)
            setState {
                copy(
                    groups = groups,
                    selectedGroupId = selected,
                    selectedGuid = repo.selectedGuid(),
                )
            }
            if (!firstPageReady.isCompleted) firstPageReady.complete(Unit)
        }
    }

    private suspend fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = state.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) repo.setSelectedGroupId(resolved)
        return resolved
    }

    private fun initialize() {
        if (initialized) return
        initialized = true
        launch(onError = {}) {
            firstPageReady.await()
            repo.prepare()
        }
    }

    private fun handleResult(result: BaseResult) {
        val confirmRemove = repo.confirmRemove()
        val doubleColumn = repo.doubleColumnDisplay()
        setState { copy(confirmRemove = confirmRemove, doubleColumnDisplay = doubleColumn) }
        if (result.refreshList) repo.refreshGroups()
        if (result.restartService && state.isRunning) restartCore()
    }

    private fun startCore() {
        if (state.selectedGuid.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        platform(
            MainEvent.StartService(
                requireVpnPermission = repo.isVpnMode(),
                requireLocalNetwork = repo.isProxySharing(),
            )
        )
    }

    private fun restartCore() {
        if (state.selectedGuid.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        platform(
            MainEvent.RestartService(
                stopFirst = state.isRunning,
                requireVpnPermission = repo.isVpnMode(),
                requireLocalNetwork = repo.isProxySharing(),
            )
        )
    }

    private fun observeServiceEvents() = launch(onError = {}) {
        repo.serviceEvents.collect(::handleServiceEvent)
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> onRunningChanged(true, keepTestingText = true)
            MainServiceEvent.StateNotRunning -> onRunningChanged(false, keepTestingText = true)
            MainServiceEvent.StateStartSuccess -> {
                toastSuccess(R.string.toast_services_success)
                onRunningChanged(true)
            }
            is MainServiceEvent.StateStartFailure -> {
                if (event.errorMessage.isNotBlank()) toastError(event.errorMessage)
                else toastError(R.string.toast_services_failure)
                onRunningChanged(false)
            }
            MainServiceEvent.StateStopSuccess -> onRunningChanged(false)
            MainServiceEvent.WarnInsecure -> toastError(R.string.toast_allow_insecure_deprecated)
            is MainServiceEvent.MeasureDelayResult -> onCurrentTestResult(event.requestId, event.result)
            is MainServiceEvent.MeasureDelayCanceled -> onCurrentTestCanceled(event.requestId)
            is MainServiceEvent.MeasureConfigNotify -> onBatchProgress(event.requestId, event.progress)
            // Row delays now arrive through the profile_stats PagingSource invalidation.
            is MainServiceEvent.MeasureConfigSuccess -> Unit
            is MainServiceEvent.MeasureConfigFinish -> releaseBatch(event.requestId)
            is MainServiceEvent.MeasureConfigCanceled -> releaseBatch(event.requestId)
        }
    }

    private fun onRunningChanged(running: Boolean, keepTestingText: Boolean = false) {
        currentTestId = null
        setState {
            copy(
                isRunning = running,
                status = when {
                    keepTestingText && isTesting -> status
                    running -> MainStatus.Connected
                    else -> MainStatus.Disconnected
                },
            )
        }
    }

    private fun newRequestId(): String = UUID.randomUUID().toString()

    private fun testCurrentServer() {
        val requestId = newRequestId()
        currentTestId = requestId
        setState { copy(status = MainStatus.Testing) }
        launch(onError = { onCurrentTestCanceled(requestId) }) {
            repo.testCurrentServer(requestId)
        }
    }

    private fun onCurrentTestResult(requestId: String, result: ConnectionTestResult) {
        if (requestId != currentTestId) return
        currentTestId = null
        if (batchTestId != null) return
        setState { copy(status = MainStatus.ConnectionTest(result)) }
    }

    private fun onCurrentTestCanceled(requestId: String) {
        if (requestId != currentTestId) return
        currentTestId = null
        applyIdleStatus()
    }

    private fun applyIdleStatus() {
        if (batchTestId != null) return
        if (currentTestId != null) {
            setState { copy(status = MainStatus.Testing) }
            return
        }
        setState { copy(status = if (isRunning) MainStatus.Connected else MainStatus.Disconnected) }
    }

    private fun selectGroup(id: String) {
        if (state.groups.none { it.id == id }) return
        if (state.selectedGroupId == id) return
        setState { copy(selectedGroupId = id) }
        launch(onError = {}) { repo.setSelectedGroupId(id) }
    }

    private fun setSearchActive(active: Boolean) {
        if (active == state.isSearchActive) return
        setState { copy(isSearchActive = active) }
        if (!active) filterConfig("")
    }

    /** Substring LIKE, not regex: filtering moved into SQL. */
    private fun filterConfig(text: String) {
        if (text == state.searchQuery) return
        setState { copy(searchQuery = text) }
        query.value = text
    }

    private fun selectServer(guid: String) {
        if (guid == state.selectedGuid) return
        launch(onError = {}) {
            withContext(NonCancellable) { repo.setSelectedGuid(guid) }
            setState { copy(selectedGuid = guid) }
            if (state.isRunning) restartCore()
        }
    }

    private fun moveServer(action: MainAction.MoveServer) = launch(onError = {}) {
        withContext(NonCancellable) {
            repo.moveServer(action.groupId, query.value, action.movedGuid, action.toIndex)
        }
    }

    private fun removeServer(guid: String) {
        if (guid == state.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        launch(loading = true) {
            repo.removeServers(listOf(guid))
            refreshSelection()
            toastSuccess()
        }
    }

    private fun removeAllServers() = launch(loading = true) {
        val groupId = state.selectedGroupId
        val count = if (groupId.isEmpty() && state.searchQuery.isEmpty()) {
            repo.removeAllServers()
        } else {
            repo.removeServers(repo.guidsInScope(groupId, state.searchQuery))
        }
        refreshSelection()
        toast(BaseText.of(R.string.title_del_config_count, count))
    }

    private fun removeDuplicateServers() = launch(loading = true) {
        val count = repo.removeDuplicateServers(state.selectedGroupId, state.searchQuery)
        refreshSelection()
        toast(BaseText.of(R.string.title_del_duplicate_config_count, count))
    }

    private fun removeInvalidServers() = launch(loading = true) {
        val count = repo.removeInvalidServers(state.selectedGroupId, state.searchQuery)
        refreshSelection()
        toast(BaseText.of(R.string.title_del_config_count, count))
    }

    /** Deletions can repoint SELECTED_SERVER inside the DAO transaction. */
    private fun refreshSelection() = setState { copy(selectedGuid = repo.selectedGuid()) }

    private fun sortByTestResults() = launch(loading = true) {
        val groups = if (state.selectedGroupId.isEmpty()) emptyList() else listOf(state.selectedGroupId)
        repo.sortByTestResults(groups)
        toastSuccess()
    }

    private fun exportAll() = launch(loading = true) {
        val guids = repo.guidsInScope(state.selectedGroupId, state.searchQuery)
        val count = repo.exportToClipboard(guids)
        if (count > 0) toast(BaseText.of(R.string.title_export_config_count, count)) else toastError()
    }

    private suspend fun importBatchConfig(configText: String) {
        if (configText.isBlank()) { toastError(); return }
        val (count, countSub) = repo.importBatchConfig(configText, state.selectedGroupId)
        when {
            count > 0 -> toast(BaseText.of(R.string.title_import_config_count, count))
            countSub > 0 -> Unit
            else -> toastError()
        }
    }

    private fun updateSubscriptions() = launch(loading = true) {
        val result = repo.updateSubscriptions(state.selectedGroupId)
        val total = result.successCount + result.failureCount + result.skipCount
        when {
            total == 0 -> toast(R.string.title_update_subscription_no_subscription)
            result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                toast(BaseText.of(R.string.title_update_config_count, result.configCount))
            else -> toast(
                BaseText.of(
                    R.string.title_update_subscription_result,
                    result.configCount, result.successCount, result.failureCount, result.skipCount,
                )
            )
        }
        refreshSelection()
    }

    private fun testAll(onlyTcp: Boolean) {
        val groupId = state.selectedGroupId
        val previousId = batchTestId
        val requestId = newRequestId()
        batchTestId = requestId
        batchGroupId = groupId
        setState { copy(isTesting = true, status = MainStatus.Testing) }
        launch(onError = { releaseBatch(requestId) }) {
            val targets = repo.guidsInScope(groupId, state.searchQuery)
            if (targets.isEmpty()) {
                releaseBatch(requestId)
                return@launch
            }
            if (previousId != null) repo.cancelBatchTest(previousId)
            repo.clearTestResults(targets)
            // An explicit guid list is only needed when the visible set is narrower than the group.
            val guids = if (state.searchQuery.isNotEmpty()) targets else emptyList()
            repo.startBatchTest(requestId, groupId, guids, onlyTcp)
        }
    }

    private fun onBatchProgress(requestId: String, progress: String) {
        if (requestId != batchTestId) return
        setState { copy(status = MainStatus.TestProgress(progress)) }
    }

    private fun cancelTesting() {
        val batchId = batchTestId
        currentTestId = null
        releaseBatch(batchId)
        launch(onError = {}) { batchId?.let { repo.cancelBatchTest(it) } }
    }

    private fun releaseBatch(requestId: String?): Boolean {
        if (requestId == null || requestId != batchTestId) return false
        batchTestId = null
        batchGroupId = null
        setState { copy(isTesting = false) }
        applyIdleStatus()
        return true
    }

    /** The row index comes from SQL and honours the active search term. */
    private fun locateSelectedServer() = launch(onError = {}) {
        val guid = repo.selectedGuid() ?: return@launch
        val groups = state.groups
        if (groups.isEmpty()) return@launch
        val ownerId = repo.subscriptionIdOf(guid)
        val groupId = groups.firstOrNull { it.id.isNotEmpty() && it.id == ownerId }?.id
            ?: groups.firstOrNull { it.id.isEmpty() }?.id
            ?: return@launch toastError()
        val index = repo.indexOf(groupId, state.searchQuery, guid) ?: return@launch toastError()
        platform(MainEvent.LocateProfile(LocateTarget(groupId = groupId, index = index)))
    }

    override fun onCleared() {
        currentTestId = null
        batchTestId = null
        batchGroupId = null
        super.onCleared()
    }
}
