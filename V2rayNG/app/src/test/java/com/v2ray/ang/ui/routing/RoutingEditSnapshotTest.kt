package com.v2ray.ang.ui.routing

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.repository.RoutingRepository
import com.v2ray.ang.dto.RoutingEditData
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingEditSnapshotTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val loaded = RulesetItem(id = "rule", remarks = "loaded", sortOrder = 123L)
    private val repo = mock<RoutingRepository> {
        onBlocking { loadEditData(any()) } doReturn RoutingEditData(loaded, true)
        onBlocking { updateRule(any()) } doReturn true
        onBlocking { insertRule(any()) } doReturn "created"
        onBlocking { removeRule(any()) } doReturn true
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun saveReadsAllCurrentBuffersAndLockWithoutCollectorOrSecondClickOverwritingIt() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        val seed = checkNotNull(vm.uiState.value.initialForm)
        val editor = RoutingEditorState().apply { initialize(seed) }
        val submitted = RoutingForm(
            remarks = "submitted",
            locked = true,
            domain = "geosite:google, domain:example.com",
            ip = "geoip:private, 1.1.1.1",
            process = "com.first, com.second",
            protocol = "http, tls",
            network = "udp",
            port = "53,443",
            outboundTag = " exit "
        )
        submitted.textValues().forEach { (key, value) -> editor.text[key].setTextAndPlaceCursorAtEnd(value) }
        editor.locked = true

        vm.onAction(RoutingEditAction.Save(editor.snapshot()))
        RoutingField.entries.forEach { editor[it].setTextAndPlaceCursorAtEnd("second click") }
        editor.locked = false
        vm.onAction(RoutingEditAction.Save(editor.snapshot()))
        runCurrent()

        val item = argumentCaptor<RulesetItem>()
        verify(repo).updateRule(item.capture())
        assertEquals(RulesetItem(
            id = "rule",
            sortOrder = 123L,
            remarks = "submitted",
            locked = true,
            domain = listOf("geosite:google", "domain:example.com"),
            ip = listOf("geoip:private", "1.1.1.1"),
            process = listOf("com.first", "com.second"),
            protocol = listOf("http", "tls"),
            network = "udp",
            port = "53,443",
            outboundTag = "exit"
        ), item.firstValue)
        assertEquals(seed, vm.uiState.value.initialForm)
    }

    @Test
    fun processNavigationUsesTheCurrentFieldInsteadOfTheLoadSeed() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        val editor = RoutingEditorState().apply { initialize(checkNotNull(vm.uiState.value.initialForm)) }
        editor[RoutingField.PROCESS].setTextAndPlaceCursorAtEnd(" com.new,com.new, ,com.last ")

        vm.onAction(editor.selectProcessAction())

        val route = (vm.events.first() as BaseEvent.Navigate).route as AppRoute.AppPicker
        assertEquals(listOf("com.new", "com.last"), route.selected)
        assertEquals("", vm.uiState.value.initialForm?.process)
    }

    @Test
    fun preLoadSaveIsIgnoredAndLateLoadCannotOverwriteAnInitializedUiDraft() = runTest(dispatcher) {
        val load = CompletableDeferred<RoutingEditData>()
        whenever(repo.loadEditData(any())).doSuspendableAnswer { load.await() }
        val editor = RoutingEditorState().apply { initialize(RoutingForm(remarks = "restored", locked = true)) }
        val vm = viewModel()
        vm.onAction(RoutingEditAction.Save(RoutingForm()))
        runCurrent()
        assertFalse(vm.uiState.value.isReady)
        assertNull(vm.uiState.value.initialForm)
        load.complete(RoutingEditData(loaded, true))
        runCurrent()

        assertTrue(vm.uiState.value.isReady)
        assertFalse(editor.initialize(checkNotNull(vm.uiState.value.initialForm)))
        assertEquals("restored", editor.snapshot().remarks)
        assertTrue(editor.locked)
        verify(repo, never()).updateRule(any())
    }

    @Test
    fun newRuleSavesTheSubmittedFormAndInvalidRemarksDoNotPersist() = runTest(dispatcher) {
        whenever(repo.loadEditData("")).thenReturn(RoutingEditData(null, true))
        val vm = viewModel(id = "")
        runCurrent()
        vm.onAction(RoutingEditAction.Save(RoutingForm(remarks = "")))
        runCurrent()
        assertEquals("", vm.uiState.value.fieldErrors[RoutingField.REMARKS]?.submittedValue)
        verify(repo, never()).insertRule(any())

        vm.onAction(RoutingEditAction.Save(RoutingForm(remarks = "new", locked = true)))
        runCurrent()
        val item = argumentCaptor<RulesetItem>()
        verify(repo).insertRule(item.capture())
        assertEquals("new", item.firstValue.remarks)
        assertEquals(true, item.firstValue.locked)
        assertTrue(vm.uiState.value.fieldErrors.isEmpty())
    }

    @Test
    fun missingRuleCannotBeSavedOrDeleted() = runTest(dispatcher) {
        whenever(repo.loadEditData(any())).thenReturn(RoutingEditData(null, false))
        val vm = viewModel()
        runCurrent()
        vm.onAction(RoutingEditAction.Save(RoutingForm(remarks = "attempt")))
        vm.onAction(RoutingEditAction.ConfirmDelete)
        runCurrent()

        assertFalse(vm.uiState.value.isReady)
        verify(repo, never()).updateRule(any())
        verify(repo, never()).removeRule(any())
    }

    @Test
    fun confirmedDeletionStillWaitsForTheDocumentLoad() = runTest(dispatcher) {
        val load = CompletableDeferred<RoutingEditData>()
        whenever(repo.loadEditData(any())).doSuspendableAnswer { load.await() }
        val vm = viewModel()
        vm.onAction(RoutingEditAction.ConfirmDelete)
        runCurrent()
        verify(repo, never()).removeRule(any())
        load.complete(RoutingEditData(loaded, true))
        runCurrent()
        verify(repo).removeRule("rule")
    }

    @Test
    fun recreatedViewModelStartsTheDropdownFilterFreshWithoutChangingTheFormSeed() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(AppRoute.EXTRA_RULE_ID to "rule"))
        val first = viewModel(handle = handle)
        runCurrent()
        val seed = first.uiState.value.initialForm
        first.slices.onQueryChange("outbound filter")

        val recreated = viewModel(handle = handle)
        runCurrent()

        assertEquals("", recreated.slices.query.value)
        assertEquals(seed, recreated.uiState.value.initialForm)
        verify(repo, never()).updateRule(any())
    }

    private fun viewModel(
        id: String = "rule",
        handle: SavedStateHandle = SavedStateHandle(mapOf(AppRoute.EXTRA_RULE_ID to id))
    ): RoutingEditViewModel = RoutingEditViewModel(handle, repo).also { store.put("rule", it) }
}
