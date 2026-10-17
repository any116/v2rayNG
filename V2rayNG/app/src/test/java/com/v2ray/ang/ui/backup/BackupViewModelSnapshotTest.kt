package com.v2ray.ang.ui.backup

import java.io.File
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.data.entities.WebDavConfig
import com.v2ray.ang.data.repository.BackupRepository
import com.v2ray.ang.ui.base.BaseEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelSnapshotTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val loaded = WebDavConfig("https://example.test/loaded")
    private val repo = mock<BackupRepository> {
        onBlocking { loadWebDav() } doReturn loaded
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun openingAndSavingCannotUseAnEmptySeedBeforeTheSettingsLoad() = runTest(dispatcher) {
        val load = CompletableDeferred<WebDavConfig?>()
        whenever(repo.loadWebDav()).doSuspendableAnswer { load.await() }
        val vm = BackupViewModel(repo).also { store.put("backup", it) }
        val events = mutableListOf<BaseEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.onAction(BackupAction.WebDavClicked)
        vm.onAction(BackupAction.SaveWebDav(WebDavConfig("")))
        runCurrent()
        assertFalse(vm.uiState.value.isWebDavLoaded)
        assertFalse(events.any { it is BackupEvent.ShowWebDavEditor })
        verify(repo, never()).saveWebDav(any())

        load.complete(loaded)
        runCurrent()
        vm.onAction(BackupAction.WebDavClicked)
        runCurrent()

        assertTrue(vm.uiState.value.isWebDavLoaded)
        assertEquals(loaded.toForm(), vm.uiState.value.webDav)
        assertTrue(events.any { it is BackupEvent.ShowWebDavEditor })
    }

    @Test
    fun successiveConfirmationsPersistTheirOwnSnapshotsInOrder() = runTest(dispatcher) {
        val first = WebDavConfig("https://example.test/first", password = " first password ")
        val second = WebDavConfig("https://example.test/second", password = "second")
        val firstWrite = CompletableDeferred<Unit>()
        whenever(repo.saveWebDav(first)).doSuspendableAnswer { firstWrite.await() }
        val vm = BackupViewModel(repo).also { store.put("backup", it) }
        runCurrent()

        vm.onAction(BackupAction.SaveWebDav(first))
        vm.onAction(BackupAction.SaveWebDav(second))
        runCurrent()
        verify(repo).saveWebDav(first)
        verify(repo, never()).saveWebDav(second)
        firstWrite.complete(Unit)
        runCurrent()

        verify(repo).saveWebDav(second)
        assertEquals(second.toForm(), vm.uiState.value.webDav)
    }

    @Test
    fun openingWhileSaveIsPendingCannotReadThePreviousPersistedSnapshot() = runTest(dispatcher) {
        val snapshot = WebDavConfig("https://example.test/saved")
        val save = CompletableDeferred<Unit>()
        whenever(repo.saveWebDav(snapshot)).doSuspendableAnswer { save.await() }
        val vm = BackupViewModel(repo).also { store.put("backup", it) }
        val events = mutableListOf<BaseEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()

        vm.onAction(BackupAction.SaveWebDav(snapshot))
        runCurrent()
        vm.onAction(BackupAction.WebDavClicked)
        vm.onAction(BackupAction.ChannelSelected(BackupChannel.WEBDAV, restoring = false))
        runCurrent()

        assertFalse(events.any { it is BackupEvent.ShowWebDavEditor })
        assertEquals(loaded.toForm(), vm.uiState.value.webDav)
        verify(repo, never()).packToCache()

        save.complete(Unit)
        runCurrent()
        vm.onAction(BackupAction.WebDavClicked)
        runCurrent()

        assertTrue(events.any { it is BackupEvent.ShowWebDavEditor })
        assertEquals(snapshot.toForm(), vm.uiState.value.webDav)
    }

    @Test
    fun channelSelectionKeepsBackupAndRestoreIntent() = runTest(dispatcher) {
        val vm = BackupViewModel(repo).also { store.put("backup", it) }
        val events = mutableListOf<BaseEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        whenever(repo.defaultFileName()).doReturn("backup.zip")
        runCurrent()

        vm.onAction(BackupAction.ChannelSelected(BackupChannel.LOCAL, restoring = true))
        runCurrent()
        assertTrue(events.any { it is BackupEvent.PickFile })

        vm.onAction(BackupAction.ChannelSelected(BackupChannel.LOCAL, restoring = false))
        runCurrent()
        assertTrue(events.any { it is BackupEvent.CreateDocument })
    }

    @Test
    fun channelSelectionRoutesWebDavBackupAndRestoreSeparately() = runTest(dispatcher) {
        val archive = File("backup.zip")
        whenever(repo.packToCache()).doSuspendableAnswer { archive }
        whenever(repo.uploadBackup(any(), any())).doSuspendableAnswer { true }
        whenever(repo.downloadBackup(any())).doSuspendableAnswer { null }
        val vm = BackupViewModel(repo).also { store.put("backup", it) }
        runCurrent()

        vm.onAction(BackupAction.ChannelSelected(BackupChannel.WEBDAV, restoring = false))
        runCurrent()
        verify(repo).uploadBackup(any(), any())

        vm.onAction(BackupAction.ChannelSelected(BackupChannel.WEBDAV, restoring = true))
        runCurrent()
        verify(repo).downloadBackup(any())
    }

    @Test
    fun clearingViewModelDoesNotDropQueuedConfirmedSnapshots() = runTest(dispatcher) {
        val first = WebDavConfig("https://example.test/first")
        val second = WebDavConfig("https://example.test/second")
        val firstWrite = CompletableDeferred<Unit>()
        whenever(repo.saveWebDav(first)).doSuspendableAnswer { firstWrite.await() }
        val vm = BackupViewModel(repo).also { store.put("backup", it) }
        runCurrent()

        vm.onAction(BackupAction.SaveWebDav(first))
        vm.onAction(BackupAction.SaveWebDav(second))
        runCurrent()
        store.clear()

        firstWrite.complete(Unit)
        runCurrent()

        verify(repo).saveWebDav(first)
        verify(repo).saveWebDav(second)
    }
}
