package com.v2ray.ang.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogcatReaderTest {

    @Test
    fun commandUsesBoundedEpochOutputAndSeparateFilters() {
        assertEquals(
            listOf(
                "logcat", "-d", "-v", "time", "-v", "epoch", "-t", "2000", "-s",
                "GoLog:V", "com.v2ray.ang.fdroid:V", "AndroidRuntime:V", "System.err:V"
            ),
            logcatReadCommand("com.v2ray.ang.fdroid")
        )
    }

    @Test
    fun aNonzeroExitIsNotTreatedAsAnEmptySuccessfulRead() {
        assertThrows(IOException::class.java) {
            runBlocking {
                withContext(Dispatchers.IO) {
                    readLogcatProcess(listOf("sh", "-c", "printf 'Permission denied\\n'; exit 1"))
                }
            }
        }
    }

    @Test
    fun timeoutKillsAChildBlockedBeforeItsFirstLine() {
        val child = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        try {
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        readLogcatProcess(emptyList(), 150) { child }
                    }
                }
            }
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun cancellationKillsTheChildAndReleasesTheReader() = runBlocking {
        val child = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        val started = CountDownLatch(1)
        try {
            val read = async(Dispatchers.IO) {
                readLogcatProcess(emptyList()) {
                    started.countDown()
                    child
                }
            }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            read.cancelAndJoin()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun oversizedLinesAreDiscardedWithoutLosingFollowingEntries() {
        val raw = "1800000000.000 E/GoLog   ( 1234): ok"
        val reader = StringReader("x".repeat(LOGCAT_MAX_LINE_CHARS + 1) + "\n$raw\n")
        val buffer = LogcatBuffer()

        readLogcatLines(reader, buffer) {}

        assertEquals(listOf(raw), buffer.snapshot(1_800_000_000_000L, 0))
    }

    @Test
    fun readsAnUnterminatedFinalLine() {
        val raw = "1800000000.000 E/GoLog   ( 1234): ok"
        val buffer = LogcatBuffer()

        readLogcatLines(StringReader(raw), buffer) {}

        assertEquals(listOf(raw), buffer.snapshot(1_800_000_000_000L, 0))
    }
}
