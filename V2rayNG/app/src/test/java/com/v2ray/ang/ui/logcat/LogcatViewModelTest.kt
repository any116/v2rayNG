package com.v2ray.ang.ui.logcat

import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.v2ray.ang.data.repository.LOGCAT_RETENTION_MS
import com.v2ray.ang.data.repository.LogcatRepository
import com.v2ray.ang.data.repository.retainLogcatLines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class LogcatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

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
    fun observesOnlyWhileStartedAndDropsTheStoppedCache() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher)
        val vm = viewModel(repo)
        val rows = collectRows(vm)
        assertEquals(0, repo.readCount)

        vm.onAction(LogcatAction.Started)
        vm.onAction(LogcatAction.Started)
        runCurrent()
        assertEquals(1, repo.readCount)
        assertEquals(2, rows.size)

        vm.onAction(LogcatAction.Stopped)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, rows.size)
        assertEquals(1, repo.readCount)

        vm.onAction(LogcatAction.Started)
        runCurrent()
        assertEquals(2, repo.readCount)
        assertEquals(2, rows.size)
    }

    @Test
    fun idleErrorsExpireEvenWhenTheNextReadIsStillPending() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher)
        val vm = viewModel(repo)
        val rows = collectRows(vm)
        vm.onAction(LogcatAction.Started)
        runCurrent()
        assertEquals(2, rows.size)
        repo.now += LOGCAT_RETENTION_MS
        repo.readBlock = { awaitCancellation() }

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, rows.size)
        assertEquals(2, repo.readCount)
    }

    @Test
    fun clearPreemptsAReadAndOldLogsDoNotReturnOnRefresh() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher)
        var readCancelled = false
        repo.readBlock = {
            try {
                awaitCancellation()
            } finally {
                readCancelled = true
            }
        }
        val vm = viewModel(repo)
        val rows = collectRows(vm)
        vm.onAction(LogcatAction.Started)
        runCurrent()

        vm.onAction(LogcatAction.Clear)
        runCurrent()

        assertTrue(readCancelled)
        assertEquals(1, repo.clearCount)
        assertEquals(0, rows.size)
        repo.readBlock = { repo.logs }
        vm.onAction(LogcatAction.Refresh)
        runCurrent()
        assertEquals(0, rows.size)

        repo.now += 1
        repo.logs = repo.logs + "1800000000.001 E/GoLog   ( 1234): new error"
        vm.onAction(LogcatAction.Refresh)
        runCurrent()
        assertEquals(listOf("new error"), rows.snapshot().items.map { it.content })
    }

    @Test
    fun failedClearKeepsTheSnapshotAndDoesNotReportSuccess() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher).apply { clearSucceeds = false }
        val vm = viewModel(repo)
        val rows = collectRows(vm)
        vm.onAction(LogcatAction.Started)
        runCurrent()
        val before = rows.snapshot().items

        vm.onAction(LogcatAction.Clear)
        runCurrent()

        assertEquals(before, rows.snapshot().items)
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun searchRemainsActiveAcrossRefreshAndClosingItIsImmediate() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher)
        val vm = viewModel(repo)
        val rows = collectRows(vm)
        vm.onAction(LogcatAction.Started)
        runCurrent()
        vm.onAction(LogcatAction.SearchOpened)
        vm.onAction(LogcatAction.QueryChanged("ERROR"))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("error"), rows.snapshot().items.map { it.content })

        vm.onAction(LogcatAction.Refresh)
        runCurrent()
        assertEquals(listOf("error"), rows.snapshot().items.map { it.content })
        assertTrue(vm.uiState.value.searchActive)

        vm.onAction(LogcatAction.SearchClosed)
        runCurrent()
        assertEquals(2, rows.size)
        assertEquals("", vm.uiState.value.query)
    }

    @Test
    fun expiredRowsCannotBeCopiedOrSharedBetweenRefreshes() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher)
        val vm = viewModel(repo)
        val rows = collectRows(vm)
        vm.onAction(LogcatAction.Started)
        runCurrent()
        val raw = rows.snapshot().items.first().raw
        repo.now += LOGCAT_RETENTION_MS

        vm.onAction(LogcatAction.CopyAll)
        vm.onAction(LogcatAction.LineLongPressed(raw))
        vm.onAction(LogcatAction.Share)
        runCurrent()

        assertTrue(repo.copied.isEmpty())
        assertEquals(0, repo.shareCount)
    }

    @Test
    fun refreshAndSearchPreserveDuplicateRecordIds() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher)
        val duplicate = repo.logs.first()
        repo.logs = listOf(duplicate, duplicate) + repo.logs.drop(1)
        val vm = viewModel(repo)
        val rows = collectRows(vm)
        vm.onAction(LogcatAction.Started)
        runCurrent()
        val before = rows.snapshot().items.filter { it.raw == duplicate }.map { it.id }
        assertEquals(2, before.toSet().size)

        repo.logs = listOf("1800000000.000 I/GoLog   ( 1234): new connection") + repo.logs
        vm.onAction(LogcatAction.Refresh)
        runCurrent()
        assertEquals(before, rows.snapshot().items.filter { it.raw == duplicate }.map { it.id })

        vm.onAction(LogcatAction.QueryChanged("ERROR"))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(before, rows.snapshot().items.map { it.id })
    }

    @Test
    fun copyAndShareIncludeMatchingRowsBeyondTheLoadedPages() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher)
        repo.logs = List(240) { index ->
            "1800000000.000 E/GoLog   ( 1234): ${if (index % 2 == 0) "error" else "connected"} $index"
        }
        val expected = repo.logs.filter { it.contains("error") }
        val vm = viewModel(repo)
        val rows = collectRows(vm)
        vm.onAction(LogcatAction.Started)
        runCurrent()
        vm.onAction(LogcatAction.QueryChanged("ERROR"))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(expected.size, rows.size)
        assertTrue(rows.snapshot().items.size < expected.size)

        vm.onAction(LogcatAction.CopyAll)
        vm.onAction(LogcatAction.Share)
        runCurrent()

        assertEquals(listOf(expected.joinToString("\n")), repo.copied)
        assertEquals(listOf(expected), repo.shared)
    }

    private fun TestScope.collectRows(vm: LogcatViewModel): PagingDataPresenter<LogLine> {
        val presenter = object : PagingDataPresenter<LogLine>(dispatcher) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<LogLine>) = Unit
        }
        backgroundScope.launch(dispatcher) {
            vm.lines.collectLatest { presenter.collectFrom(it) }
        }
        return presenter
    }

    private fun viewModel(repo: FakeRepository): LogcatViewModel =
        LogcatViewModel(repo, dispatcher).also { store.put("logcat", it) }

    private class FakeRepository(io: CoroutineDispatcher) : LogcatRepository(mock(), mock(), io) {
        var now = 1_800_000_000_000L
        var clearedAt = 0L
        var logs = listOf(
            "1800000000.000 E/GoLog   ( 1234): error",
            "1799999999.999 I/GoLog   ( 1234): connected"
        )
        var readBlock: suspend () -> List<String> = { logs }
        var clearSucceeds = true
        var readCount = 0
        var clearCount = 0
        var shareCount = 0
        val copied = mutableListOf<String>()
        val shared = mutableListOf<List<String>>()

        override suspend fun read(): List<String> {
            readCount++
            return retain(readBlock())
        }

        override suspend fun clear(): Boolean {
            clearCount++
            if (clearSucceeds) clearedAt = now
            return clearSucceeds
        }

        override fun retain(lines: List<String>): List<String> = retainLogcatLines(lines, now, clearedAt)

        override suspend fun copyToClipboard(text: String) {
            copied += text
        }

        override suspend fun writeShareFile(lines: List<String>): String? {
            shareCount++
            shared += lines
            return "shared.txt"
        }
    }
}
