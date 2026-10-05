package com.v2ray.ang.data.repository

import java.io.File
import java.io.IOException

internal const val LOGCAT_EXPORT_RETENTION_MS = 24 * 60 * 60 * 1000L
internal const val LOGCAT_MAX_EXPORTS = 3
private const val EXPORT_PREFIX = "v2rayNG_logcat_"

internal class LogcatExports(cacheDir: File) {
    private val directory = File(cacheDir, "shared_logs")

    fun write(lines: List<String>, now: Long): File {
        prune(now)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create log export directory")
        val file = File.createTempFile(EXPORT_PREFIX, ".txt", directory)
        try {
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                lines.forEach { writer.append(it).append('\n') }
            }
            file.setLastModified(now)
            prune(now, keep = file)
            return file
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }
    }

    fun prune(now: Long, keep: File? = null) {
        runCatching {
            val files = ownedFiles().sortedByDescending { it.lastModified() }
            val valid = files.filter { file ->
                if (file != keep && now - file.lastModified() >= LOGCAT_EXPORT_RETENTION_MS) {
                    file.delete()
                    false
                } else {
                    true
                }
            }
            valid.filter { it != keep }.drop(LOGCAT_MAX_EXPORTS - if (keep == null) 0 else 1)
                .forEach { runCatching { it.delete() } }
        }
    }

    fun clear() {
        ownedFiles().forEach { runCatching { it.delete() } }
    }

    private fun ownedFiles(): List<File> = runCatching {
        directory.listFiles()?.filter {
            it.isFile && it.name.startsWith(EXPORT_PREFIX) && it.name.endsWith(".txt")
        }.orEmpty()
    }.getOrDefault(emptyList())
}
