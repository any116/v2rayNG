package com.v2ray.ang.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.StringReader

/**
 * Disposable JSON-lines spool, not an application data store. Source parsing finishes here before
 * the Room replay transaction; close deletes the file on success, failure and cancellation.
 */
internal class ImportBuffer<T : Any>(private val cacheDir: File, private val entryType: Class<T>) : Closeable {
    private var file: File? = null
    private var writer: BufferedWriter? = null
    private var isSealed = false

    var count: Int = 0
        private set

    fun append(entry: T) {
        check(!isSealed) { "Import buffer is already sealed" }
        val output = writer ?: File.createTempFile("profile-import-", ".jsonl", cacheDir)
            .also { file = it }.bufferedWriter().also { writer = it }
        output.write(JsonUtil.toJson(entry))
        output.newLine()
        count++
    }

    fun openReader(): BufferedReader {
        writer?.close()
        writer = null
        isSealed = true
        return file?.bufferedReader() ?: StringReader("").buffered()
    }

    /** Reader ownership stays with the caller so abandoning a Sequence never leaks an open file. */
    fun entries(reader: BufferedReader): Sequence<T> = reader.lineSequence().map { line ->
        JsonUtil.fromJson(line, entryType) ?: throw IOException("Invalid staged import entry")
    }.constrainOnce()

    override fun close() {
        runCatching { writer?.close() }
            .onFailure { LogUtil.e(AppConfig.TAG, "Failed to close import buffer", it) }
        writer = null
        isSealed = true
        runCatching {
            file?.let { if (it.exists() && !it.delete()) throw IOException("Failed to delete import buffer") }
        }.onFailure { LogUtil.e(AppConfig.TAG, "Failed to clean import buffer", it) }
    }

    companion object {
        private const val STALE_IMPORT_AGE_MS = 60 * 60 * 1000L
        private val TEMP_PREFIXES = listOf("profile-import-", "subscription-download-")

        /** Removes files left by a killed process; fresh files are left for an active import. */
        fun cleanupStaleFiles(cacheDir: File, now: Long = System.currentTimeMillis()) {
            cacheDir.listFiles()
                ?.filter { file ->
                    TEMP_PREFIXES.any(file.name::startsWith)
                        && now - file.lastModified() > STALE_IMPORT_AGE_MS
                }
                ?.forEach { file ->
                    runCatching { if (file.exists() && !file.delete()) throw IOException("Failed to delete stale import file") }
                        .onFailure { LogUtil.e(AppConfig.TAG, "Failed to clean stale import file", it) }
                }
        }
    }
}
