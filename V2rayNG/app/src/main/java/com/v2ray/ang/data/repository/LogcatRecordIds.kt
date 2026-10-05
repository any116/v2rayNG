package com.v2ray.ang.data.repository

import com.v2ray.ang.dto.LogcatRecord
import java.util.concurrent.atomic.AtomicLong

internal class LogcatRecordIds {
    private val sequence = AtomicLong()

    /** Match oldest occurrences first so newly appended identical logs get their own fresh IDs. */
    fun identify(raw: List<String>, previous: List<LogcatRecord>): List<LogcatRecord> {
        val candidates = HashMap<String, ArrayDeque<LogcatRecord>>()
        previous.asReversed().forEach { record ->
            candidates.getOrPut(record.raw) { ArrayDeque() }.addLast(record)
        }
        return raw.asReversed().map { line ->
            candidates[line]?.removeFirstOrNull() ?: LogcatRecord(sequence.incrementAndGet(), line)
        }.asReversed()
    }
}
