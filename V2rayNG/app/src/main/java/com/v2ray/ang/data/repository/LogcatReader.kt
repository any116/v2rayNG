package com.v2ray.ang.data.repository

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.Reader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val LOGCAT_MAX_LINE_CHARS = 16 * 1024
private const val LOGCAT_READ_TIMEOUT_MS = 5000L

internal fun logcatReadCommand(tag: String): List<String> = listOf(
    "logcat", "-d", "-v", "time", "-v", "epoch", "-t", LOGCAT_MAX_LINES.toString(),
    "-s", "GoLog:V", "$tag:V", "AndroidRuntime:V", "System.err:V"
)

/** Cancellation destroys the child so a blocked pipe read can finish. Call on the IO dispatcher. */
internal suspend fun readLogcatProcess(
    command: List<String>,
    timeoutMillis: Long = LOGCAT_READ_TIMEOUT_MS,
    startProcess: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() }
): LogcatBuffer = withTimeout(timeoutMillis) {
    suspendCancellableCoroutine { continuation ->
        var process: Process? = null
        try {
            val child = startProcess(command)
            process = child
            continuation.invokeOnCancellation { runCatching { child.destroy() } }
            val buffer = LogcatBuffer()
            child.inputStream.reader(Charsets.UTF_8).use { reader ->
                readLogcatLines(reader, buffer) {
                    if (!continuation.isActive) throw kotlinx.coroutines.CancellationException()
                }
            }
            val exitCode = child.waitFor()
            if (exitCode != 0) throw IOException("logcat exited with code $exitCode")
            continuation.resume(buffer)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        } finally {
            process?.let { child ->
                runCatching { child.destroy() }
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
                runCatching { child.outputStream.close() }
            }
        }
    }
}

internal fun readLogcatLines(reader: Reader, buffer: LogcatBuffer, checkActive: () -> Unit) {
    val chunk = CharArray(4096)
    val line = StringBuilder()
    var oversized = false
    while (true) {
        checkActive()
        val count = reader.read(chunk)
        if (count < 0) break
        for (index in 0 until count) {
            val char = chunk[index]
            if (char == '\n') {
                if (!oversized) buffer.add(line.toString().trimEnd('\r'))
                line.setLength(0)
                oversized = false
            } else if (!oversized) {
                if (line.length < LOGCAT_MAX_LINE_CHARS) line.append(char) else oversized = true
            }
        }
    }
    if (!oversized && line.isNotEmpty()) buffer.add(line.toString().trimEnd('\r'))
}
