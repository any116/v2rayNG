package com.v2ray.ang.ui.main

import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.data.repository.MainRepository
import com.v2ray.ang.data.repository.MainServiceEvent
import com.v2ray.ang.data.repository.SubRepository
import com.v2ray.ang.ui.components.closeSearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val queries = mutableListOf<String>()
    private val repo = mock<MainRepository> {
        on { serviceEvents } doReturn MutableSharedFlow<MainServiceEvent>()
    }
    private val subRepo = mock<SubRepository> {
        on { groupRemoved } doReturn MutableSharedFlow<String>()
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(repo.observeCounts(any())).thenAnswer { invocation ->
            queries += invocation.getArgument<String>(0)
            flowOf(emptyMap<String, Int>())
        }
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun nonemptySearchIsDebouncedAndClearReachesCountsImmediately() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.serverCount("").collect {} }
        runCurrent()
        assertEquals(listOf(""), queries)
        vm.onAction(MainAction.SetSearchActive(true))
        vm.onAction(MainAction.Search("literal [.*"))
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertEquals(listOf(""), queries)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("", "literal [.*"), queries)

        vm.onAction(MainAction.Search(""))
        runCurrent()
        assertEquals(listOf("", "literal [.*", ""), queries)
        assertTrue(vm.uiState.value.isSearchActive)
    }

    @Test
    fun closeCancelsPendingSearchWithoutWaitingForItsDebounce() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.serverCount("").collect {} }
        runCurrent()
        vm.onAction(MainAction.SetSearchActive(true))
        vm.onAction(MainAction.Search("pending"))
        runCurrent()
        advanceTimeBy(299)

        val input = TextFieldState("pending plus a last edit")
        closeSearchField(input) { vm.onAction(MainAction.SetSearchActive(false)) }
        assertEquals("", input.text.toString())
        assertEquals("", vm.uiState.value.searchQuery)
        assertFalse(vm.uiState.value.isSearchActive)
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(listOf(""), queries)
    }

    private fun viewModel(): MainViewModel = MainViewModel(repo, subRepo).also { store.put("main", it) }
}
