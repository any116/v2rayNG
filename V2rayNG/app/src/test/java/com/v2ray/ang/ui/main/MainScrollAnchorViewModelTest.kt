package com.v2ray.ang.ui.main

import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.data.repository.MainRepository
import com.v2ray.ang.data.repository.MainServiceEvent
import com.v2ray.ang.data.repository.SubRepository
import com.v2ray.ang.ui.base.BaseEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainScrollAnchorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repo = mock<MainRepository> {
        on { serviceEvents } doReturn MutableSharedFlow<MainServiceEvent>()
        on { serviceState } doReturn MutableStateFlow<Boolean?>(null)
    }
    private val subRepo = mock<SubRepository> {
        on { groupRemoved } doReturn MutableSharedFlow<String>()
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun requestCapturesItsFilterAndReturnsACorrelatedEventWithoutSelectingAnotherGroup() = runTest(dispatcher) {
        whenever(repo.indexOf("a", "saved-filter", "guid")).thenReturn(64)
        val vm = MainViewModel(repo, subRepo).also { store.put("main", it) }
        val events = mutableListOf<BaseEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.onAction(MainAction.RestoreScrollAnchor("request", "a", "guid", "saved-filter"))
        vm.onAction(MainAction.Search("new-filter"))
        runCurrent()

        verify(repo).indexOf("a", "saved-filter", "guid")
        assertEquals(listOf(MainEvent.ScrollAnchorResolved("request", 64)), events)
        assertEquals("", vm.uiState.value.selectedGroupId)
        assertEquals("new-filter", vm.uiState.value.searchQuery)
    }

    @Test
    fun missingRowReturnsNullSoTheUiCanUseItsNumericFallback() = runTest(dispatcher) {
        whenever(repo.indexOf("a", "", "deleted")).thenReturn(null)
        val vm = MainViewModel(repo, subRepo).also { store.put("main", it) }
        val events = mutableListOf<BaseEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.onAction(MainAction.RestoreScrollAnchor("request", "a", "deleted", ""))
        runCurrent()
        assertEquals(listOf(MainEvent.ScrollAnchorResolved("request", null)), events)
    }

    @Test
    fun cancellingADisposedPagesLookupDoesNotProduceALateScrollEvent() = runTest(dispatcher) {
        val release = CompletableDeferred<Int?>()
        whenever(repo.indexOf("a", "", "guid")).doSuspendableAnswer { release.await() }
        val vm = MainViewModel(repo, subRepo).also { store.put("main", it) }
        val events = mutableListOf<BaseEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.onAction(MainAction.RestoreScrollAnchor("request", "a", "guid", ""))
        runCurrent()
        vm.onAction(MainAction.CancelScrollAnchorRestore("request"))
        release.complete(64)
        runCurrent()
        assertTrue(events.isEmpty())
    }
}
