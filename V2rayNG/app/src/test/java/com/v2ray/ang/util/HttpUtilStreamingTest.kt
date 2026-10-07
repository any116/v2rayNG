package com.v2ray.ang.util

import android.util.Log
import com.v2ray.ang.dto.UrlContentRequest
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.MockedStatic
import org.mockito.Mockito
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HttpUtilStreamingTest {

    private lateinit var logs: MockedStatic<Log>

    @BeforeEach
    fun mockAndroidLogs() {
        logs = Mockito.mockStatic(Log::class.java, Mockito.RETURNS_DEFAULTS)
    }

    @AfterEach
    fun closeAndroidLogs() {
        logs.close()
    }

    @TempDir
    lateinit var cacheDir: File

    @Test
    fun streamingDownloadPreservesRedirectHeadersAndResponseCharset() {
        val userAgent = AtomicReference<String>()
        val customHeader = AtomicReference<String>()
        val text = "trojan://password@example.com:443#节点"
        val server = LocalServer(2) { path, headers ->
            if (path == "/redirect") {
                Reply(302, mapOf("Location" to "/subscription"))
            } else {
                userAgent.set(headers["user-agent"])
                customHeader.set(headers["x-subscription"])
                Reply(200, mapOf("Content-Type" to "text/plain; charset=utf-16"), text.toByteArray(Charsets.UTF_16))
            }
        }
        server.use {
            val file = cacheDir.resolve("download.txt")
            val count = HttpUtil.downloadUrlContentWithUserAgent(
                UrlContentRequest(
                    url = server.url("/redirect"),
                    timeout = 3000,
                    userAgent = "import-test",
                    requestHeaders = """{"X-Subscription":"present"}"""
                ),
                file
            )

            assertEquals(text.length.toLong(), count)
            assertEquals(text, file.readText())
            assertEquals("import-test", userAgent.get())
            assertEquals("present", customHeader.get())
            server.awaitRequests()
        }
    }

    @Test
    fun cancelledDownloadDoesNotSwallowCancellation() {
        val server = LocalServer(1) { _, _ ->
            Reply(200, body = "x".repeat(32_000).toByteArray())
        }
        server.use {
            assertThrows(CancellationException::class.java) {
                HttpUtil.downloadUrlContentWithUserAgent(
                    UrlContentRequest(
                        url = server.url("/subscription"),
                        timeout = 3000,
                        userAgent = "import-test"
                    ),
                    cacheDir.resolve("download.txt")
                ) { throw CancellationException("cancelled") }
            }
        }
    }

    @Test
    fun streamingDownloadRejectsBodiesAboveTheConfiguredLimit() {
        val server = LocalServer(1) { _, _ -> Reply(200, body = "0123456789".toByteArray()) }
        server.use {
            assertThrows(java.io.IOException::class.java) {
                HttpUtil.downloadUrlContentWithUserAgent(
                    UrlContentRequest(url = server.url("/subscription"), timeout = 3000),
                    cacheDir.resolve("download.txt"),
                    maxChars = 4
                )
            }
        }
    }

    private data class Reply(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0)
    )

    /** Plain java.net keeps the local fixture available on Android unit-test compile classpaths. */
    private class LocalServer(
        requestCount: Int,
        reply: (String, Map<String, String>) -> Reply
    ) : Closeable {
        private val listener = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        private val executor = Executors.newSingleThreadExecutor()
        private val task = executor.submit {
            repeat(requestCount) {
                listener.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val path = reader.readLine().split(' ')[1]
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase(Locale.ROOT)] = line.substringAfter(':').trim()
                    }
                    val response = reply(path, headers)
                    val headerText = buildString {
                        append("HTTP/1.1 ${response.status} OK\r\n")
                        append("Connection: close\r\nContent-Length: ${response.body.size}\r\n")
                        response.headers.forEach { (key, value) -> append("$key: $value\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().use { output ->
                        output.write(headerText.toByteArray(Charsets.ISO_8859_1))
                        output.write(response.body)
                    }
                }
            }
        }

        fun url(path: String): String = "http://127.0.0.1:${listener.localPort}$path"

        fun awaitRequests() {
            task.get(5, TimeUnit.SECONDS)
        }

        override fun close() {
            listener.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
