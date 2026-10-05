package com.v2ray.ang.ui.main

import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.data.repository.MainRepository
import com.v2ray.ang.data.repository.MainServiceEvent
import com.v2ray.ang.data.repository.SubRepository
import com.v2ray.ang.dto.GroupMapItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainGroupSelectionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val groups = MutableStateFlow(listOf(group(""), group("a"), group("b")))
    private val removals = MutableSharedFlow<String>()
    private var savedGroupId = ""

    private val repo = mock<MainRepository> {
        on { serviceEvents } doReturn MutableSharedFlow<MainServiceEvent>()
        on { observeGroups() } doReturn groups
    }
    private val subRepo = mock<SubRepository> {
        on { groupRemoved } doReturn removals
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(repo.selectedGroupId()).thenAnswer { savedGroupId }
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun rapidSelectionsPersistInOrderAndRecreatedViewModelRestoresTheLastOne() = runTest(dispatcher) {
        val allowFirstWrite = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        whenever(repo.setSelectedGroupId(any())).doSuspendableAnswer { invocation ->
            val id = invocation.getArgument<String>(0)
            started += id
            if (id == "a") allowFirstWrite.await()
            savedGroupId = id
            Unit
        }
        val vm = viewModel()
        vm.awaitReady()
        runCurrent()

        vm.onAction(MainAction.SelectGroup("a"))
        runCurrent()
        vm.onAction(MainAction.SelectGroup("b"))
        runCurrent()
        assertEquals("b", vm.uiState.value.selectedGroupId)
        assertEquals(listOf("a"), started)

        allowFirstWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b"), started)
        assertEquals("b", savedGroupId)

        store.clear()
        val restored = viewModel()
        restored.awaitReady()
        runCurrent()
        assertEquals("b", restored.uiState.value.selectedGroupId)
    }

    @Test
    fun queuedSelectionSurvivesViewModelClear() = runTest(dispatcher) {
        val allowFirstWrite = CompletableDeferred<Unit>()
        whenever(repo.setSelectedGroupId(any())).doSuspendableAnswer { invocation ->
            val id = invocation.getArgument<String>(0)
            if (id == "a") allowFirstWrite.await()
            savedGroupId = id
            Unit
        }
        val vm = viewModel()
        vm.awaitReady()
        runCurrent()
        vm.onAction(MainAction.SelectGroup("a"))
        runCurrent()
        vm.onAction(MainAction.SelectGroup("b"))
        runCurrent()

        store.clear()
        allowFirstWrite.complete(Unit)
        runCurrent()
        assertEquals("b", savedGroupId)
    }

    @Test
    fun removingSelectedGroupQueuesFallbackAfterEarlierSelectionWrites() = runTest(dispatcher) {
        val allowWrite = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        whenever(repo.setSelectedGroupId(any())).doSuspendableAnswer { invocation ->
            val id = invocation.getArgument<String>(0)
            started += id
            if (id == "a") allowWrite.await()
            savedGroupId = id
            Unit
        }
        val vm = viewModel()
        vm.awaitReady()
        runCurrent()
        vm.onAction(MainAction.SelectGroup("a"))
        runCurrent()

        removals.emit("a")
        runCurrent()
        assertEquals("", vm.uiState.value.selectedGroupId)
        assertEquals(listOf("a"), started)

        allowWrite.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", ""), started)
        assertEquals("", savedGroupId)
    }

    @Test
    fun transientGroupListGapKeepsTheSavedSelection() = runTest(dispatcher) {
        savedGroupId = "b"
        val vm = viewModel()
        vm.awaitReady()
        runCurrent()

        groups.value = listOf(group(""))
        runCurrent()
        assertEquals("b", vm.uiState.value.selectedGroupId)
        assertEquals("b", savedGroupId)

        groups.value = listOf(group(""), group("b"), group("a"))
        runCurrent()
        assertEquals("b", vm.uiState.value.selectedGroupId)
        assertEquals("b", savedGroupId)
    }

    @Test
    fun hidingAllExplicitlySelectsTheFirstVisibleGroup() = runTest(dispatcher) {
        whenever(repo.setSelectedGroupId(any())).doSuspendableAnswer { invocation ->
            savedGroupId = invocation.getArgument(0)
            Unit
        }
        val vm = viewModel()
        vm.awaitReady()
        runCurrent()

        groups.value = listOf(group("a"), group("b"))
        runCurrent()
        assertEquals("a", vm.uiState.value.selectedGroupId)
        assertEquals("a", savedGroupId)
    }

    private fun viewModel(): MainViewModel = MainViewModel(repo, subRepo).also { store.put("main", it) }

    private fun group(id: String) = GroupMapItem(id = id, remarks = id)
}
