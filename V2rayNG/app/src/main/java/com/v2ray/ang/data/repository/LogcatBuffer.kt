package com.v2ray.ang.data.repository

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

internal const val LOGCAT_RETENTION_MS = 6 * 60 * 60 * 1000L
internal const val LOGCAT_MAX_LINES = 2000
internal const val LOGCAT_MAX_BYTES = 1024 * 1024

private val epochHeader = Regex("""^\s*(\d{1,12})\.(\d{3,9})\s+[VDIWEFAS]/.*?\(\s*\d+\):""")
private val legacyStamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.US)
    .withResolverStyle(ResolverStyle.STRICT)

internal fun logcatTimestamp(raw: String): Long? {
    val match = epochHeader.find(raw) ?: return null
    val seconds = match.groupValues[1].toLongOrNull() ?: return null
    val millis = match.groupValues[2].take(3).toLong()
    return seconds * 1000 + millis
}

internal fun logcatClearedAt(value: String?, now: Long): Long {
    if (value == null) return 0
    value.toLongOrNull()?.let { return if (it in 1..now + 1) it else 0 }
    val zone = ZoneId.systemDefault()
    val year = Instant.ofEpochMilli(now).atZone(zone).year
    for (candidate in year downTo year - 1) {
        try {
            val parsed = LocalDateTime.parse("$candidate-$value", legacyStamp)
                .atZone(zone).toInstant().toEpochMilli()
            if (parsed <= now + 1) return parsed.coerceAtLeast(0)
        } catch (_: DateTimeParseException) {
            continue
        }
    }
    return 0
}

internal class LogcatBuffer(
    private val maxLines: Int = LOGCAT_MAX_LINES,
    private val maxBytes: Int = LOGCAT_MAX_BYTES
) {
    private data class Entry(val timestamp: Long, val raw: String, val bytes: Int)

    private val entries = ArrayDeque<Entry>()
    private var bytes = 0

    fun add(raw: String) {
        val timestamp = logcatTimestamp(raw) ?: return
        val size = raw.toByteArray(Charsets.UTF_8).size
        if (size > maxBytes) return
        entries.addLast(Entry(timestamp, raw, size))
        bytes += size
        while (entries.size > maxLines || bytes > maxBytes) {
            bytes -= entries.removeFirst().bytes
        }
    }

    fun snapshot(now: Long, clearedAt: Long): List<String> {
        val cutoff = maxOf(now - LOGCAT_RETENTION_MS, clearedAt)
        return entries.asReversed().filter { it.timestamp > cutoff && it.timestamp <= now }.map { it.raw }
    }
}

internal fun retainLogcatLines(lines: List<String>, now: Long, clearedAt: Long): List<String> {
    val buffer = LogcatBuffer()
    lines.asReversed().forEach(buffer::add)
    return buffer.snapshot(now, clearedAt)
}
