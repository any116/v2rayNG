package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.UrlContentRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

object HttpUtil {

    /**
     * The single connection pool and task dispatcher shared by every request in this process.
     */
    val sharedClient: OkHttpClient by lazy { OkHttpClient() }

    /**
     * Converts the domain part of a URL string to its IDN (Punycode, ASCII Compatible Encoding) format.
     *
     * For example, a URL like "https://例子.中国/path" will be converted to "https://xn--fsqu00a.xn--fiqs8s/path".
     *
     * @param str The URL string to convert (can contain non-ASCII characters in the domain).
     * @return The URL string with the domain part converted to ASCII-compatible (Punycode) format.
     */
    fun toIdnUrl(str: String): String {
        val host = URL(str).host
        val asciiHost = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
        return if (host == asciiHost) str else str.replace(host, asciiHost)
    }

    /**
     * Converts a Unicode domain name to its IDN (Punycode, ASCII Compatible Encoding) format.
     * If the input is an IP address or already an ASCII domain, returns the original string.
     *
     * @param domain The domain string to convert (can include non-ASCII internationalized characters).
     * @return The domain in ASCII-compatible (Punycode) format, or the original string if input is an IP or already ASCII.
     */
    fun toIdnDomain(domain: String): String {
        // Return as is if it's a pure IP address (IPv4 or IPv6) or already ASCII (plain or punycode).
        if (Utils.isPureIpAddress(domain) || domain.all { it.code < 128 }) return domain
        return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED)
    }

    /**
     * Resolves a hostname to an IP address, returns original input if it's already an IP
     *
     * @param host The hostname or IP address to resolve
     * @param ipv6Preferred Whether to prefer IPv6 addresses, defaults to false
     * @return The resolved IP address or the original input (if it's already an IP or resolution fails)
     */
    fun resolveHostToIP(host: String, ipv6Preferred: Boolean = false): List<String>? {
        // An address that is already an IP needs no resolution.
        if (Utils.isPureIpAddress(host)) return null
        try {
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) return null

            val sortedAddresses = if (ipv6Preferred) {
                addresses.sortedByDescending { it is Inet6Address }
            } else {
                addresses.sortedBy { it is Inet6Address }
            }
            val ipList = sortedAddresses.mapNotNull { it.hostAddress }
            LogUtil.i(AppConfig.TAG, "Resolved IPs for $host: ${ipList.joinToString()}")
            return ipList
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve host to IP", e)
            return null
        }
    }


    /**
     * Retrieves the content of a URL as a string.
     *
     * @param url The URL to fetch content from.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @return The content of the URL as a string.
     */
    fun getUrlContent(request: UrlContentRequest): String? {
        val url = request.url ?: return null
        val client = buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = true)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Connection", "close")
        applyProxyAuthorizationHeader(request, requestBuilder)
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Failed to get URL content, code=${response.code}")
                    return null
                }
                response.body.string()
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get URL content", e)
            null
        }
    }

    /**
     * Retrieves the content of a URL as a string with a custom User-Agent header.
     *
     * @param url The URL to fetch content from.
     * @param timeout The timeout value in milliseconds.
     * @param httpPort The HTTP port to use.
     * @return The content of the URL as a string.
     * @throws IOException If an I/O error occurs.
     */
    @Throws(IOException::class)
    fun getUrlContentWithUserAgent(request: UrlContentRequest): String =
        readUrlContentWithUserAgent(request) { it?.string().orEmpty() }

    /** Streams a subscription to a disposable spool; [checkActive] keeps long downloads cancellable. */
    @Throws(IOException::class)
    fun downloadUrlContentWithUserAgent(
        request: UrlContentRequest,
        target: File,
        checkActive: () -> Unit = {},
        maxChars: Long = AppConfig.MAX_IMPORT_CONTENT_CHARS
    ): Long = readUrlContentWithUserAgent(request) { body ->
        if (body != null && body.contentLength() > maxChars) {
            throw IOException("Response body exceeds import limit")
        }
        target.bufferedWriter().use { output ->
            // charStream honors the response charset/BOM just like the former body.string().
            body?.charStream()?.use { input ->
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    checkActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (total + size > maxChars) throw IOException("Response body exceeds import limit")
                    output.write(buffer, 0, size)
                    total += size
                }
                total
            } ?: 0L
        }
    }

    private fun <T> readUrlContentWithUserAgent(request: UrlContentRequest, consume: (ResponseBody?) -> T): T {
        var currentUrl = request.url
        var redirects = 0
        val maxRedirects = 3

        while (redirects++ < maxRedirects) {
            val requestUrl = currentUrl ?: throw IOException("Subscription URL is missing")
            val client = buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = false)
            val finalUserAgent = request.userAgent?.takeIf { it.isNotBlank() } ?: "v2rayNG/${BuildConfig.VERSION_NAME}"
            val requestBuilder = Request.Builder()
                .url(requestUrl)
                .get()
                .header("User-agent", finalUserAgent)
                .header("Connection", "close")

            applyEmbeddedBasicAuthHeader(requestUrl, requestBuilder)

            val headersMap = JsonUtil.parseHeadersToMap(request.requestHeaders)
            for ((key, value) in headersMap) {
                LogUtil.d(AppConfig.TAG, "Adding custom header: $key = $value")
                runCatching { requestBuilder.header(key, value) }
            }

            applyProxyAuthorizationHeader(request, requestBuilder)

            client.newCall(requestBuilder.build()).execute().use { response ->
                when {
                    response.isRedirect -> {
                        val location = response.header("Location")
                        if (location.isNullOrEmpty()) {
                            throw IOException("Redirect location not found")
                        }
                        currentUrl = resolveLocation(requestUrl, location)
                        if (currentUrl.isNullOrEmpty()) {
                            throw IOException("Failed to resolve redirect location")
                        }
                        continue
                    }

                    response.isSuccessful -> {
                        return consume(response.body)
                    }

                    else -> {
                        throw IOException("Request failed with status code ${response.code}")
                    }
                }
            }
        }
        throw IOException("Too many redirects")
    }

    /** Upstream proxies only apply when a local HTTP port is configured. */
    private fun applyProxyAuthorizationHeader(request: UrlContentRequest, requestBuilder: Request.Builder) {
        if (request.httpPort == 0) return
        val username = request.proxyUsername
        val password = request.proxyPassword
        if (username.isNullOrBlank() || password.isNullOrBlank()) return
        requestBuilder.header("Proxy-Authorization", Credentials.basic(username, password))
    }

    private fun applyEmbeddedBasicAuthHeader(rawUrl: String, requestBuilder: Request.Builder) {
        val userInfo = runCatching { URL(rawUrl).userInfo }.getOrNull() ?: return
        val user = userInfo.substringBefore(':')
        val pass = if (':' in userInfo) userInfo.substringAfter(':') else ""
        requestBuilder.header(
            "Authorization",
            Credentials.basic(decodeOrRaw(user), decodeOrRaw(pass)),
        )
    }

    private fun decodeOrRaw(value: String): String =
        runCatching { Utils.decodeURIComponent(value) }.getOrDefault(value)

    private fun buildOkHttpClient(
        timeout: Int,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?,
        followRedirects: Boolean
    ): OkHttpClient {
        val builder = sharedClient.newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)

        RootCertificateManager.configure(builder)

        if (httpPort != 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, httpPort)))
            if (!proxyUsername.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                builder.proxyAuthenticator { _, response ->
                    // Retrying a request that already carried credentials would loop forever.
                    if (response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(proxyUsername, proxyPassword))
                        .build()
                }
            }
        }

        return builder.build()
    }

    private fun resolveLocation(baseUrl: String, raw: String): String? =
        runCatching {
            val locUri = URI(raw)
            val resolved = if (locUri.isAbsolute) locUri else URI(baseUrl).resolve(locUri)
            resolved.toURL().toString()
        }.getOrNull()
            ?: runCatching { URL(raw).toString() }.getOrNull()
            ?: runCatching { URL(URL(baseUrl), raw).toString() }.getOrNull()

    fun downloadToFile(
        request: UrlContentRequest,
        targetFile: File
    ): Boolean {
        val url = request.url ?: return false
        val client = buildOkHttpClient(request.timeout, request.httpPort, request.proxyUsername, request.proxyPassword, followRedirects = true)
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .header("Connection", "close")
        applyProxyAuthorizationHeader(request, requestBuilder)

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtil.w(AppConfig.TAG, "Failed to download file, code=${response.code}, url=$url")
                    return false
                }
                response.body.byteStream().use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to download file: $url", e)
            false
        }
    }
}
