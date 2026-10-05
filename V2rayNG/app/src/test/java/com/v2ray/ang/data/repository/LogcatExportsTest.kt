package com.v2ray.ang.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LogcatExportsTest {

    @TempDir
    lateinit var cache: File

    @Test
    fun consecutiveSharesHaveUniquePathsAndKeepEarlierFilesReadable() {
        val exports = LogcatExports(cache)
        val first = exports.write(listOf("first"), 1000)
        val second = exports.write(listOf("second"), 1000)

        assertTrue(first != second)
        assertEquals("first\n", first.readText())
        assertEquals("second\n", second.readText())
    }

    @Test
    fun cleanupRemovesExpiredExportsButLeavesUnrelatedCacheFiles() {
        val exports = LogcatExports(cache)
        val old = exports.write(listOf("old"), 1000)
        val unrelated = File(cache, "unrelated.txt").apply { writeText("keep") }

        exports.prune(1000 + LOGCAT_EXPORT_RETENTION_MS)

        assertFalse(old.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun exportCountStaysBoundedAndTheNewestFileSurvives() {
        val exports = LogcatExports(cache)
        val files = (0..LOGCAT_MAX_EXPORTS).map { exports.write(listOf("log $it"), 1000L + it) }

        assertEquals(LOGCAT_MAX_EXPORTS, files.count { it.exists() })
        assertFalse(files.first().exists())
        assertTrue(files.last().exists())
    }

    @Test
    fun clearDeletesOnlyOwnedExports() {
        val exports = LogcatExports(cache)
        val shared = exports.write(listOf("private log"), 1000)
        val unrelated = File(shared.parentFile, "other.txt").apply { writeText("keep") }

        exports.clear()

        assertFalse(shared.exists())
        assertTrue(unrelated.exists())
    }
}
