package com.v2ray.ang.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LogcatBufferTest {

    private val now = 1_800_000_000_000L

    @Test
    fun clearWatermarkSurvivesReopeningAndExcludesTheBoundary() {
        val clearedAt = logcatClearedAt((now - 100).toString(), now)
        val buffer = LogcatBuffer()
        buffer.add(line(now - 101, "old"))
        buffer.add(line(now - 100, "boundary"))
        buffer.add(line(now - 99, "new"))

        assertEquals(listOf(line(now - 99, "new")), buffer.snapshot(now, clearedAt))
    }

    @Test
    fun legacyClearWatermarkUsesTheCurrentYearAndHandlesNewYear() {
        val zone = ZoneId.systemDefault()
        val january = ZonedDateTime.of(2026, 1, 1, 0, 0, 1, 0, zone).toInstant().toEpochMilli()
        val december = ZonedDateTime.of(2025, 12, 31, 23, 59, 59, 999_000_000, zone)
            .toInstant().toEpochMilli()

        assertEquals(december, logcatClearedAt("12-31 23:59:59.999", january))
        assertEquals(january, logcatClearedAt("01-01 00:00:01.000", january))
        assertEquals(january + 1, logcatClearedAt("01-01 00:00:01.001", january))
    }

    @Test
    fun malformedWatermarksDoNotDisableRetention() {
        listOf(null, "", "-1", "not a date", "02-30 12:00:00.000", (now + 2).toString(),
            "999999999999999999999").forEach {
            assertEquals(0L, logcatClearedAt(it, now))
        }
    }

    @Test
    fun idleErrorsExpireWithoutNewLogs() {
        val buffer = LogcatBuffer()
        buffer.add(line(now, "error"))

        assertEquals(listOf(line(now, "error")), buffer.snapshot(now, 0))
        assertTrue(buffer.snapshot(now + LOGCAT_RETENTION_MS, 0).isEmpty())
    }

    @Test
    fun ignoresDividersDiagnosticsExpiredAndFutureEntries() {
        val buffer = LogcatBuffer()
        buffer.add("--------- beginning of main")
        buffer.add("logcat: Permission denied")
        buffer.add(line(now - LOGCAT_RETENTION_MS, "expired"))
        buffer.add(line(now + 1, "future"))
        buffer.add(line(now - 1, "stack frame"))

        assertEquals(listOf(line(now - 1, "stack frame")), buffer.snapshot(now, 0))
    }

    @Test
    fun boundsLinesAndReturnsNewestFirstWithoutDeduplicatingErrors() {
        val buffer = LogcatBuffer(maxLines = 2)
        buffer.add(line(now - 1, "older"))
        repeat(2) { buffer.add(line(now, "same error")) }

        assertEquals(List(2) { line(now, "same error") }, buffer.snapshot(now, 0))
    }

    @Test
    fun boundsUtf8BytesAndKeepsTheNewestEntries() {
        val first = line(now - 1, "错误")
        val second = line(now, "错误")
        val buffer = LogcatBuffer(maxBytes = second.toByteArray(Charsets.UTF_8).size)
        buffer.add(first)
        buffer.add(second)

        assertEquals(listOf(second), buffer.snapshot(now, 0))
    }

    @Test
    fun oversizedEntryDoesNotEvictValidLogs() {
        val small = line(now, "ok")
        val buffer = LogcatBuffer(maxBytes = small.toByteArray(Charsets.UTF_8).size)
        buffer.add(small)
        buffer.add(line(now, "x".repeat(1000)))

        assertEquals(listOf(small), buffer.snapshot(now, 0))
    }

    @Test
    fun parsesEpochFractionsAndRejectsInvalidHeaders() {
        assertEquals(now + 123, logcatTimestamp("  1800000000.123456 E/GoLog   ( 1234): error"))
        assertEquals(null, logcatTimestamp("1800000000.123 not a log record"))
    }

    @Test
    fun retainDoesNotReverseAnExistingSnapshot() {
        val lines = listOf(line(now, "new"), line(now - 1, "older"))

        assertEquals(lines, retainLogcatLines(lines, now, 0))
        assertTrue(retainLogcatLines(lines, now, now).isEmpty())
    }

    private fun line(at: Long, message: String): String =
        "${at / 1000}.${(at % 1000).toString().padStart(3, '0')} E/GoLog   ( 1234): $message"
}
