package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.ui.base.BaseScreen
import com.v2ray.ang.ui.components.LocalPredictiveBackProgress
import com.v2ray.ang.ui.components.ObserveQuery
import com.v2ray.ang.ui.components.rememberSearchFieldController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val BottomBarHorizontalMargin = 8.dp
private val BottomBarBottomMargin = 12.dp
private val ListBottomGap = 12.dp
private const val LocateViewportDivisor = 3
private const val LocateLayoutTimeoutMs = 600L

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onPlatformEvent: (MainEvent) -> Boolean
) {
    val scope = rememberCoroutineScope()
    val predictiveBackState = LocalPredictiveBackProgress.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val drawerState = rememberMainPushDrawerState()
    DisposableEffect(lifecycleOwner, predictiveBackState, drawerState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                predictiveBackState?.onBackFinished()
                drawerState.onDestinationResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val dispatch = remember(viewModel) { viewModel::onAction }
    val search = rememberSearchFieldController(
        initialQuery = "",
        initialActive = false
    )
    val openSearch = remember(search, dispatch) {
        { search.open { dispatch(MainAction.SetSearchActive(true)) } }
    }
    val closeSearch = remember(search, dispatch) {
        { search.close { dispatch(MainAction.SetSearchActive(false)) } }
    }
    // Deliberately remember, not rememberSaveable: a restored process must seed the new ViewModel again.
    var searchReady by remember(viewModel, search) { mutableStateOf(false) }
    LaunchedEffect(search, dispatch) {
        dispatch(MainAction.SetSearchActive(search.isActive))
        dispatch(MainAction.Search(if (search.isActive) search.input.text.toString() else ""))
        // Seed the restored filter before any page requests an unfiltered PagingData generation.
        searchReady = true
    }
    search.ObserveQuery { dispatch(MainAction.Search(it)) }
    val dialogs = remember(dispatch) { MainDialogHost(dispatch) }
    val scrollStates = rememberSaveable(saver = GroupScrollStates.Saver) { GroupScrollStates() }
    val handles = remember(viewModel, dialogs, scrollStates) {
        MainScreenHandles(
            dispatch = dispatch,
            slices = MainSlices(viewModel::servers, viewModel::serverCount),
            scrollStates = scrollStates,
            showDialog = dialogs.show,
            requestRemove = dialogs.requestRemove
        )
    }
    var locateTarget by remember { mutableStateOf<LocateTarget?>(null) }
    val bottomState = rememberBottomState(viewModel)
    var bottomBarHeight by remember { mutableIntStateOf(0) }
    val bottomPadding = with(LocalDensity.current) { bottomBarHeight.toDp() } + ListBottomGap

    MainDialogs(
        dialog = dialogs.current,
        onDismiss = dialogs.dismiss,
        onAction = dispatch,
        onRequestRemove = dialogs.requestRemove
    )

    MainPushDrawer(
        state = drawerState,
        gesturesEnabled = !search.isActive && dialogs.current == null,
        drawerContent = {
            MainDrawerContent(
                onAction = { action ->
                    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED &&
                        drawerState.canStartGesture()
                    ) {
                        if (action is MainAction.Navigate) {
                            scope.launch { drawerState.navigate { dispatch(action) } }
                        } else {
                            // External actions may not pause Main (e.g. no URL handler); do not lock them.
                            dispatch(action)
                        }
                    }
                }
            )
        }
    ) {
        BaseScreen(
            viewModel = viewModel,
            onEvent = { event ->
                when (event) {
                    is MainEvent.ShowQrCode -> { dialogs.show(MainDialog.QrCode(event.bitmap)); true }
                    is MainEvent.LocateProfile -> { locateTarget = event.target; true }
                    is MainEvent.ScrollAnchorResolved -> {
                        scrollStates.resolveAnchorIndex(event.requestId, event.index)
                        true
                    }
                    is MainEvent -> onPlatformEvent(event)
                    else -> false
                }
            },
            onResult = { result -> dispatch(MainAction.ResultReceived(result)) },
            topBar = {
                val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
                val suppressAnimations = predictiveBackState?.isInProgress == true
                MainTopBar(
                    isLoading = isLoading,
                    suppressAnimations = suppressAnimations,
                    isSearchActive = search.isActive,
                    searchInput = search.input,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onSearchOpen = openSearch,
                    onSearchClose = closeSearch,
                    onAction = dispatch,
                    onShowDialog = dialogs.show,
                    bottomOverlayHeightPx = bottomBarHeight
                )
            }
        ) { state, _ ->
            SideEffect { dialogs.confirmRemove = state.confirmRemove }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (searchReady) {
                        MainContent(
                            args = MainPagerArgs(
                                groups = state.groups,
                                selectedGroupId = state.selectedGroupId,
                                selectedGuid = state.selectedGuid,
                                doubleColumnDisplay = state.doubleColumnDisplay,
                                isFiltering = state.isFiltering,
                                searchQuery = state.searchQuery
                            ),
                            handles = handles,
                            contentPadding = PaddingValues(bottom = bottomPadding),
                            locateTarget = locateTarget,
                            onLocateHandled = { locateTarget = null }
                        )
                    }
                }
                MainBottomBarOverlay(
                    bottomState = bottomState,
                    onAction = dispatch,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { bottomBarHeight = it.height }
                        .padding(
                            start = BottomBarHorizontalMargin,
                            end = BottomBarHorizontalMargin,
                            bottom = BottomBarBottomMargin
                        )
                )
            }
        }
    }
}

@Immutable
private data class MainBottomState(
    val status: MainStatus = MainStatus.Disconnected,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false
)

@Composable
private fun MainBottomBarOverlay(
    bottomState: State<MainBottomState>,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier
) {
    // Read the narrow slice here, so test progress doesn't recompose the server pager.
    val bottom by bottomState
    val suppressAnimations = LocalPredictiveBackProgress.current?.isInProgress == true
    MainBottomBar(
        statusText = bottom.status.asText(),
        isRunning = bottom.isRunning,
        isTesting = bottom.isTesting,
        onAction = onAction,
        suppressAnimations = suppressAnimations,
        modifier = modifier
    )
}

@Composable
private fun rememberBottomState(viewModel: MainViewModel): State<MainBottomState> {
    val flow = remember(viewModel) {
        viewModel.uiState
            .map { MainBottomState(it.status, it.isRunning, it.isTesting) }
            .distinctUntilChanged()
    }
    val initial = remember(viewModel) {
        viewModel.uiState.value.let { MainBottomState(it.status, it.isRunning, it.isTesting) }
    }
    return flow.collectAsStateWithLifecycle(initialValue = initial)
}

@Immutable
private data class MainPagerArgs(
    val groups: List<GroupMapItem>,
    val selectedGroupId: String,
    val selectedGuid: String?,
    val doubleColumnDisplay: Boolean,
    val isFiltering: Boolean,
    val searchQuery: String
)

@Composable
private fun MainContent(
    args: MainPagerArgs,
    handles: MainScreenHandles,
    contentPadding: PaddingValues,
    locateTarget: LocateTarget?,
    onLocateHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groups = args.groups
    if (groups.isEmpty()) return
    val groupIds = remember(groups) { groups.map { it.id } }
    // Avoid querying page 0 on a cold start. rememberPagerState can restore an older numeric
    // page instead of initialPage, so the effect below must still align by ID before observing.
    val initialPage = remember { groups.indexOfFirst { it.id == args.selectedGroupId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { groups.size })
    val scrollStates = handles.scrollStates

    LaunchedEffect(pagerState, groupIds, args.selectedGroupId) {
        scrollStates.retain(groupIds.toHashSet())
        observeGroupPagerSelection(
            groupIds = groupIds,
            selectedGroupId = args.selectedGroupId,
            settledPages = snapshotFlow { pagerState.settledPage },
            alignPage = { pagerState.scrollToPage(it) },
            onSelect = { handles.dispatch(MainAction.SelectGroup(it)) }
        )
    }

    /** The row index is computed in SQL, so no list scan and no wait for data is needed here. */
    LaunchedEffect(locateTarget) {
        val target = locateTarget ?: return@LaunchedEffect
        try {
            val page = groups.indexOfFirst { it.id == target.groupId }
            if (page !in 0 until pagerState.pageCount) {
                handles.dispatch(MainAction.LocateFailed)
                return@LaunchedEffect
            }
            // Explicit navigation/locating wins over a slower background restoration lookup.
            scrollStates.finishRestore(target.groupId, args.doubleColumnDisplay)
            if (pagerState.settledPage != page) pagerState.scrollToPage(page)

            if (args.doubleColumnDisplay) {
                val grid = scrollStates.grid(target.groupId)
                val ready = withTimeoutOrNull(LocateLayoutTimeoutMs) {
                    snapshotFlow { grid.layoutInfo.viewportSize.height to grid.layoutInfo.totalItemsCount }
                        .first { (height, count) -> height > 0 && count > target.index }
                }
                if (ready == null) {
                    handles.dispatch(MainAction.LocateFailed)
                    return@LaunchedEffect
                }
                grid.scrollToItem(
                    target.index,
                    -grid.layoutInfo.viewportSize.height / LocateViewportDivisor
                )
            } else {
                val list = scrollStates.list(target.groupId)
                val ready = withTimeoutOrNull(LocateLayoutTimeoutMs) {
                    snapshotFlow { list.layoutInfo.viewportSize.height to list.layoutInfo.totalItemsCount }
                        .first { (height, count) -> height > 0 && count > target.index }
                }
                if (ready == null) {
                    handles.dispatch(MainAction.LocateFailed)
                    return@LaunchedEffect
                }
                list.scrollToItem(
                    target.index,
                    -list.layoutInfo.viewportSize.height / LocateViewportDivisor
                )
            }
        } finally {
            onLocateHandled()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (groups.size > 1) {
            GroupTabBar(
                groups = groups,
                selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                counts = handles.slices.counts,
                onTabClick = { index ->
                    groupIds.getOrNull(index)?.let { handles.dispatch(MainAction.SelectGroup(it)) }
                }
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
            key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
        ) { page ->
            val group = groups.getOrNull(page) ?: return@HorizontalPager
            GroupPagerPage(
                groupId = group.id,
                selectedGuid = args.selectedGuid,
                doubleColumnDisplay = args.doubleColumnDisplay,
                canReorder = group.id.isNotEmpty() && !args.isFiltering,
                handles = handles,
                contentPadding = contentPadding,
                query = args.searchQuery
            )
        }
    }
}

@Composable
private fun MainStatus.asText(): String = when (this) {
    MainStatus.Disconnected -> stringResource(R.string.connection_not_connected)
    MainStatus.Connected -> stringResource(R.string.connection_connected)
    MainStatus.Testing -> stringResource(R.string.connection_test_testing)
    is MainStatus.TestProgress -> stringResource(R.string.connection_running_task_left, progress)
    is MainStatus.ConnectionTest -> formatConnectionTestResult(result)
}

@Composable
private fun formatConnectionTestResult(result: ConnectionTestResult): String {
    val status = if (result.delayMillis >= 0) {
        val delay = stringResource(R.string.server_test_delay_value, result.delayMillis)
        stringResource(R.string.connection_test_available, delay)
    } else {
        val detail = result.errorMessage.ifBlank {
            stringResource(R.string.connection_test_empty_message)
        }
        stringResource(R.string.connection_test_error, detail)
    }

    if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
        return status
    }

    val unknown = stringResource(R.string.value_unknown)
    return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
}
