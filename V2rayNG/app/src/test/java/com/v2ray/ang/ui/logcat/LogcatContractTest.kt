package com.v2ray.ang.ui.logcat

import com.v2ray.ang.dto.LogcatRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class LogcatContractTest {

    @Test
    fun epochLogsDisplayLocalTimeButKeepTheOriginalExportText() {
        val raw = "         1800000000.123 E/GoLog   ( 1234): failed (request): retry"
        val expectedTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(1_800_000_000_123L))

        val line = parseLogLine(LogcatRecord(42, raw))

        assertEquals(42L, line.id)
        assertEquals("$expectedTime E/GoLog", line.tag)
        assertEquals("failed (request): retry", line.content)
        assertEquals(raw, line.raw)
    }

    @Test
    fun legacyTimeLogsAndPreviewRowsRemainReadable() {
        val raw = "01-01 00:00:00.000 E/GoLog   ( 1234): error"

        val line = parseLogLine(LogcatRecord(43, raw))

        assertEquals("01-01 00:00:00.000 E/GoLog", line.tag)
        assertEquals("error", line.content)
        assertEquals(raw, line.raw)
    }
}
