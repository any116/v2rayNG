package com.v2ray.ang.ui.userasset

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.data.repository.UserAssetRepository
import com.v2ray.ang.ui.AppRoute
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class UserAssetUrlSnapshotTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val loaded = AssetUrlItem(guid = "asset", remarks = "loaded", url = "https://example.com/loaded")
    private val repo = mock<UserAssetRepository> {
        onBlocking { loadAsset(any()) } doReturn loaded
        onBlocking { isRemarkDuplicated(any(), any()) } doReturn false
        onBlocking { saveAsset(any(), any(), any()) } doReturn "asset"
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun saveUsesBothSubmittedFieldsAcrossDuplicateLookupAndIgnoresASecondClick() = runTest(dispatcher) {
        val duplicate = CompletableDeferred<Boolean>()
        whenever(repo.isRemarkDuplicated(any(), any())).doSuspendableAnswer { duplicate.await() }
        val vm = viewModel()
        runCurrent()
        val seed = checkNotNull(vm.uiState.value.initialForm)
        val editor = AssetEditorState().apply { initialize(seed) }
        editor[AssetField.REMARKS].setTextAndPlaceCursorAtEnd(" submitted.dat ")
        editor[AssetField.URL].setTextAndPlaceCursorAtEnd(" https://example.com/submitted.dat ")

        vm.onAction(UserAssetUrlAction.Save(editor.snapshot()))
        editor[AssetField.REMARKS].setTextAndPlaceCursorAtEnd("second.dat")
        vm.onAction(UserAssetUrlAction.Save(editor.snapshot()))
        runCurrent()
        verify(repo).isRemarkDuplicated("submitted.dat", "asset")
        editor[AssetField.URL].setTextAndPlaceCursorAtEnd("https://example.com/later.dat")
        duplicate.complete(false)
        runCurrent()

        verify(repo).saveAsset("asset", "submitted.dat", "https://example.com/submitted.dat")
        assertEquals(seed, vm.uiState.value.initialForm)
    }

    @Test
    fun saveBeforeLoadCannotQueueABlankOverwrite() = runTest(dispatcher) {
        val load = CompletableDeferred<AssetUrlItem?>()
        whenever(repo.loadAsset(any())).doSuspendableAnswer { load.await() }
        val vm = viewModel()
        vm.onAction(UserAssetUrlAction.Save(AssetForm()))
        runCurrent()
        assertFalse(vm.uiState.value.isReady)
        assertNull(vm.uiState.value.initialForm)
        load.complete(loaded)
        runCurrent()

        assertTrue(vm.uiState.value.isReady)
        assertEquals(AssetForm(loaded.remarks, loaded.url), vm.uiState.value.initialForm)
        verify(repo, never()).saveAsset(any(), any(), any())
        assertTrue(vm.uiState.value.fieldErrors.isEmpty())
    }

    @Test
    fun duplicateErrorRefersToTheSnapshotAndDisappearsForANewerDraft() = runTest(dispatcher) {
        val duplicate = CompletableDeferred<Boolean>()
        whenever(repo.isRemarkDuplicated(any(), any())).doSuspendableAnswer { duplicate.await() }
        val vm = viewModel()
        runCurrent()
        val editor = AssetEditorState().apply { initialize(checkNotNull(vm.uiState.value.initialForm)) }
        editor[AssetField.REMARKS].setTextAndPlaceCursorAtEnd("duplicate.dat")
        vm.onAction(UserAssetUrlAction.Save(editor.snapshot()))
        runCurrent()
        editor[AssetField.REMARKS].setTextAndPlaceCursorAtEnd("fixed.dat")
        duplicate.complete(true)
        runCurrent()

        val error = checkNotNull(vm.uiState.value.fieldErrors[AssetField.REMARKS])
        assertEquals(AssetFieldError(R.string.msg_remark_is_duplicate, "duplicate.dat"), error)
        assertNull(editor.error(AssetField.REMARKS, error))
        verify(repo, never()).saveAsset(any(), any(), any())
    }

    @Test
    fun invalidSnapshotValidatesBothFieldsWithoutPersisting() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.onAction(UserAssetUrlAction.Save(AssetForm(" ", "not a URL")))
        runCurrent()

        assertEquals(AssetFieldError(R.string.sub_setting_remarks, " "),
            vm.uiState.value.fieldErrors[AssetField.REMARKS])
        assertEquals(AssetFieldError(R.string.toast_invalid_url, "not a URL"),
            vm.uiState.value.fieldErrors[AssetField.URL])
        verify(repo, never()).saveAsset(any(), any(), any())
    }

    @Test
    fun obsoleteHandleTextCannotOverrideTheDatabaseAndUiDraftStillWins() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(
            AppRoute.EXTRA_ASSET_ID to "asset",
            "asset_edit_remarks" to "legacy.dat",
            "asset_edit_url" to "https://example.com/legacy.dat"
        ))
        val editor = AssetEditorState().apply { initialize(AssetForm("ui.dat", "https://example.com/ui.dat")) }
        val vm = viewModel(handle)
        runCurrent()

        assertEquals(AssetForm(loaded.remarks, loaded.url), vm.uiState.value.initialForm)
        assertFalse(editor.initialize(checkNotNull(vm.uiState.value.initialForm)))
        vm.onAction(UserAssetUrlAction.Save(editor.snapshot()))
        runCurrent()

        verify(repo).saveAsset("asset", "ui.dat", "https://example.com/ui.dat")
        assertEquals("legacy.dat", handle.get<String>("asset_edit_remarks"))
    }

    @Test
    fun qrCodeSeedsBothFieldsForANewAsset() = runTest(dispatcher) {
        val vm = viewModel(SavedStateHandle(mapOf(
            AppRoute.EXTRA_ASSET_QRCODE to "https://example.com/geo.dat"
        )))
        runCurrent()
        assertEquals(AssetForm("geo.dat", "https://example.com/geo.dat"), vm.uiState.value.initialForm)
        verify(repo, never()).loadAsset(any())
    }

    @Test
    fun missingAssetDoesNotAllowSaveOrDeletion() = runTest(dispatcher) {
        whenever(repo.loadAsset(any())).thenReturn(null)
        val vm = viewModel()
        runCurrent()
        vm.onAction(UserAssetUrlAction.Save(AssetForm("attempt", "https://example.com/a")))
        vm.onAction(UserAssetUrlAction.DialogConfirm)
        runCurrent()

        assertFalse(vm.uiState.value.isReady)
        verify(repo, never()).saveAsset(any(), any(), any())
        verify(repo, never()).removeAssetUrl(any())
    }

    @Test
    fun confirmedDeletionStillWaitsForTheDocumentLoad() = runTest(dispatcher) {
        val load = CompletableDeferred<AssetUrlItem?>()
        whenever(repo.loadAsset(any())).doSuspendableAnswer { load.await() }
        val vm = viewModel()
        vm.onAction(UserAssetUrlAction.DialogConfirm)
        runCurrent()
        verify(repo, never()).removeAssetUrl(any())
        load.complete(loaded)
        runCurrent()
        verify(repo).removeAssetUrl("asset")
    }

    private fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(mapOf(AppRoute.EXTRA_ASSET_ID to "asset"))
    ): UserAssetUrlViewModel = UserAssetUrlViewModel(handle, repo) { it.startsWith("https://") }
        .also { store.put("asset", it) }
}
