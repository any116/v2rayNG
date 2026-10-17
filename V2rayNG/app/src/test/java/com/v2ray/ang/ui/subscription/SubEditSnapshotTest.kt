package com.v2ray.ang.ui.subscription

import android.os.Bundle
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.data.repository.SubRepository
import com.v2ray.ang.dto.SubChainValidation
import com.v2ray.ang.dto.SubEditData
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.util.JsonUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SubEditSnapshotTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val loaded = SubscriptionItem(guid = "sub", remarks = "loaded", sortOrder = 42L)
    private val repo = mock<SubRepository> {
        onBlocking { loadEdit(any()) } doReturn SubEditData(loaded, true)
        onBlocking { validateChain(any(), any()) } doReturn SubChainValidation()
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun immediateSaveAndASecondClickCannotReplaceTheFirstSnapshotDuringChainValidation() = runTest(dispatcher) {
        val chain = CompletableDeferred<SubChainValidation>()
        whenever(repo.validateChain(any(), any())).doSuspendableAnswer { chain.await() }
        val vm = viewModel()
        runCurrent()
        val seed = checkNotNull(vm.uiState.value.initialForm)
        val editor = SubEditEditorState().apply { initialize(seed) }
        val expected = SubEditForm(
            remarks = "submitted",
            userAgent = "latest agent",
            requestHeaders = "X-Test: latest",
            filter = "latest.*",
            enabled = false,
            autoUpdate = true,
            updateInterval = "90",
            allowInsecureUrl = true,
            prevProfile = "entry",
            nextProfile = "exit"
        )
        expected.textValues().forEach { (key, value) -> editor.text[key].setTextAndPlaceCursorAtEnd(value) }
        editor.enabled = false
        editor.autoUpdate = true
        editor.allowInsecureUrl = true

        vm.onAction(SubEditAction.Save(editor.snapshot()))
        editor[SubField.REMARKS].setTextAndPlaceCursorAtEnd("second click")
        vm.onAction(SubEditAction.Save(editor.snapshot()))
        runCurrent()
        verify(repo).validateChain("entry", "exit")
        editor[SubField.PREV_PROFILE].setTextAndPlaceCursorAtEnd("edited while suspended")
        editor.enabled = true
        chain.complete(SubChainValidation())
        runCurrent()

        val item = argumentCaptor<SubscriptionItem>()
        verify(repo).save(eq("sub"), item.capture())
        assertEquals(expected, item.firstValue.toSubEditForm())
        assertEquals(42L, item.firstValue.sortOrder)
        assertEquals("loaded", loaded.remarks)
        assertEquals(seed, vm.uiState.value.initialForm)
    }

    @Test
    fun saveBeforeLoadIsRejectedInsteadOfQueuingAnEmptyForm() = runTest(dispatcher) {
        val load = CompletableDeferred<SubEditData>()
        whenever(repo.loadEdit(any())).doSuspendableAnswer { load.await() }
        val vm = viewModel()
        vm.onAction(SubEditAction.Save(SubEditForm()))
        runCurrent()
        assertFalse(vm.uiState.value.isReady)
        assertNull(vm.uiState.value.initialForm)

        load.complete(SubEditData(loaded, true))
        runCurrent()

        assertTrue(vm.uiState.value.isReady)
        assertEquals("loaded", vm.uiState.value.initialForm?.remarks)
        verify(repo, never()).save(any(), any())
        assertTrue(vm.uiState.value.fieldErrors.isEmpty())
    }

    @Test
    fun errorsBelongToSubmittedTextAndFlagsWithoutChangingTheLoadSeed() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        val editor = SubEditEditorState().apply { initialize(checkNotNull(vm.uiState.value.initialForm)) }
        editor[SubField.REMARKS].setTextAndPlaceCursorAtEnd("")
        editor[SubField.UPDATE_INTERVAL].setTextAndPlaceCursorAtEnd("1")
        editor.autoUpdate = true
        vm.onAction(SubEditAction.Save(editor.snapshot()))
        runCurrent()
        val errors = vm.uiState.value.fieldErrors

        assertEquals(R.string.sub_setting_remarks, editor.error(SubField.REMARKS, errors[SubField.REMARKS]))
        assertEquals(R.string.toast_invalid_update_interval,
            editor.error(SubField.UPDATE_INTERVAL, errors[SubField.UPDATE_INTERVAL]))
        editor[SubField.REMARKS].setTextAndPlaceCursorAtEnd("fixed")
        editor.autoUpdate = false
        assertNull(editor.error(SubField.REMARKS, errors[SubField.REMARKS]))
        assertNull(editor.error(SubField.UPDATE_INTERVAL, errors[SubField.UPDATE_INTERVAL]))
        assertEquals("loaded", vm.uiState.value.initialForm?.remarks)
        verify(repo, never()).save(any(), any())
    }

    @Test
    fun missingEditedSubscriptionNeverBecomesReadyOrWrites() = runTest(dispatcher) {
        whenever(repo.loadEdit(any())).thenReturn(SubEditData(null, false))
        val vm = viewModel()
        runCurrent()
        vm.onAction(SubEditAction.Save(SubEditForm(remarks = "attempt")))
        vm.onAction(SubEditAction.DeleteConfirmed)
        runCurrent()

        assertFalse(vm.uiState.value.isReady)
        verify(repo, never()).save(any(), any())
        verify(repo, never()).remove(any())
    }

    @Test
    fun deletionStillWaitsForTheLoadedDocument() = runTest(dispatcher) {
        val load = CompletableDeferred<SubEditData>()
        whenever(repo.loadEdit(any())).doSuspendableAnswer { load.await() }
        val vm = viewModel()
        vm.onAction(SubEditAction.DeleteConfirmed)
        runCurrent()
        verify(repo, never()).remove(any())
        load.complete(SubEditData(loaded, true))
        runCurrent()
        verify(repo).remove("sub")
    }

    @Test
    fun legacyFormIsALoadSeedAndDoesNotReplaceAnAlreadyRestoredDraft() = runTest(dispatcher) {
        val legacyForm = SubEditForm(remarks = "legacy", enabled = false)
        val legacy = mock<Bundle> {
            on { getBoolean("user_edited", false) } doReturn true
            on { getString("form") } doReturn JsonUtil.toJson(legacyForm)
        }
        val vm = viewModel(SavedStateHandle(mapOf(
            AppRoute.EXTRA_SUB_GUID to "sub",
            "sub_edit_saved_state" to legacy
        )))
        val editor = SubEditEditorState().apply { initialize(SubEditForm(remarks = "ui draft")) }
        runCurrent()

        assertEquals(legacyForm, vm.uiState.value.initialForm)
        assertNull(handle.get<Bundle>("sub_edit_saved_state"))
        assertFalse(editor.initialize(checkNotNull(vm.uiState.value.initialForm)))
        assertEquals("ui draft", editor.snapshot().remarks)
        assertTrue(editor.enabled)
    }

    @Test
    fun recreatedViewModelStartsDropdownFiltersFreshWithoutMirroringEditedFields() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(AppRoute.EXTRA_SUB_GUID to "sub"))
        val first = viewModel(handle)
        runCurrent()
        val seed = first.uiState.value.initialForm
        first.slices.onPrevQueryChange("entry filter")
        first.slices.onNextQueryChange("exit filter")

        val recreated = viewModel(handle)
        runCurrent()

        assertEquals("", recreated.slices.prevQuery.value)
        assertEquals("", recreated.slices.nextQuery.value)
        assertEquals(seed, recreated.uiState.value.initialForm)
        verify(repo, never()).save(any(), any())
    }

    private fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(mapOf(AppRoute.EXTRA_SUB_GUID to "sub"))
    ): SubEditViewModel = SubEditViewModel(handle, repo).also { store.put("sub", it) }
}
