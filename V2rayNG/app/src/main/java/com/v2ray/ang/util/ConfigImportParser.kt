package com.v2ray.ang.util

import android.util.Base64
import android.util.Base64InputStream
import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.ConfigImportSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.PushbackReader
import java.io.Reader
import java.io.StringReader
import java.security.MessageDigest

private const val MAX_IMPORT_LINE_CHARS = 1024
private const val SNIFF_PREFIX_CHARS = 1024
private const val PUSHBACK_BUFFER_CHARS = SNIFF_PREFIX_CHARS + 1

internal enum class ConfigImportKind { EMPTY, LINKS, JSON, WIREGUARD, BASE64 }

internal enum class ConfigImportLineKind { PROFILE, SUBSCRIPTION }

internal data class ConfigImportLine(val kind: ConfigImportLineKind, val text: String)

/**
 * Owns one single-pass reader. A complete JSON/WireGuard document and a LINKS batch are mutually
 * exclusive; a LINKS batch may contain both profiles and subscription URLs. Document readers must
 * not be consumed through [lines]. Callers must use/close the content, including on cancellation.
 */
internal class ConfigImportContent(
    val kind: ConfigImportKind,
    val reader: BufferedReader,
    private val profileSchemes: Set<String>,
    private val checkActive: () -> Unit
) : Closeable {

    init {
        require(kind != ConfigImportKind.BASE64) { "Prepared content must already have its Base64 envelope removed" }
    }

    /**
     * Emits normalized, duplicate-free entries; [source] makes nested-URL handling explicit.
     * The returned single-use sequence must be fully consumed before [close] is called.
     */
    fun lines(source: ConfigImportSource = ConfigImportSource.USER_INPUT): Sequence<ConfigImportLine> {
        check(kind == ConfigImportKind.LINKS || kind == ConfigImportKind.EMPTY) {
            "A complete configuration document cannot be consumed as a link batch"
        }
        return candidateLines()
            .mapNotNull { raw ->
                checkActive()
                ConfigImportParser.classifyLine(raw, profileSchemes)
            }
            .filter { it.kind != ConfigImportLineKind.SUBSCRIPTION || source.includeSubscriptionUrls }
            .distinctImportText(key = { it.text }, checkActive = checkActive)
            .constrainOnce()
    }

    /** Discard oversized unrecognized lines without materializing an entire nested Base64 blob. */
    private fun candidateLines(): Sequence<String> = sequence {
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        val line = StringBuilder()
        var discard = false
        while (true) {
            checkActive()
            val size = reader.read(buffer)
            if (size < 0) break
            for (index in 0 until size) {
                val char = buffer[index]
                if (char == '\r' || char == '\n') {
                    if (!discard && line.isNotEmpty()) yield(line.toString())
                    line.setLength(0)
                    discard = false
                } else if (!discard) {
                    if (line.isEmpty() && (char.isWhitespace() || char == '\uFEFF')) continue
                    line.append(char)
                    if (line.length == MAX_IMPORT_LINE_CHARS
                        && ConfigImportParser.classifyLine(line.toString(), profileSchemes) == null
                    ) {
                        discard = true
                        line.setLength(0)
                    }
                }
            }
        }
        if (!discard && line.isNotEmpty()) yield(line.toString())
    }

    override fun close() = reader.close()
}

/** A boundary can deduplicate independently without retaining another full set of input strings. */
internal fun <T> Sequence<T>.distinctImportText(
    key: (T) -> String,
    checkActive: () -> Unit = {}
): Sequence<T> = sequence {
    val digest = MessageDigest.getInstance("SHA-256")
    val seen = HashSet<ImportTextDigest>()
    for (entry in this@distinctImportText) {
        checkActive()
        if (seen.add(ImportTextDigest(digest.digest(key(entry).toByteArray(Charsets.UTF_8))))) yield(entry)
    }
}

private class ImportTextDigest(private val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is ImportTextDigest && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

internal object ConfigImportParser {

    /**
     * Clipboard text is already in memory, but decoding and splitting it must not create another
     * full subscription string or list. The reader overload also supports files/network spools.
     */
    fun parse(
        content: String?,
        profileSchemes: Set<String>,
        decodeBase64: (InputStream) -> InputStream = { Base64InputStream(it, Base64.DEFAULT) },
        checkActive: () -> Unit = {}
    ): ConfigImportContent = parse({ StringReader(content.orEmpty()) }, profileSchemes, decodeBase64, checkActive)

    /** The source is reopenable for bounded sniffing; only one outer Base64 decoder is ever applied. */
    fun parse(
        openReader: () -> Reader,
        profileSchemes: Set<String>,
        decodeBase64: (InputStream) -> InputStream = { Base64InputStream(it, Base64.DEFAULT) },
        checkActive: () -> Unit = {}
    ): ConfigImportContent {
        val schemes = profileSchemes.mapTo(linkedSetOf()) { ProtocolParserRegistry.normalizeScheme(it) }
        val kind = openReader().use { sniff(it, schemes, checkActive) }
        if (kind == ConfigImportKind.EMPTY) {
            return ConfigImportContent(kind, StringReader("").buffered(), schemes, checkActive)
        }
        val source = openReader()
        val reader = try {
            if (kind == ConfigImportKind.BASE64) {
                decodeBase64(Base64TextInputStream(source)).reader(Charsets.UTF_8)
            } else {
                source
            }
        } catch (e: Exception) {
            runCatching { source.close() }
            throw e
        }
        try {
            // Peek only a bounded prefix of decoded content. Unknown/nested Base64 becomes an
            // empty line stream rather than triggering another decoder.
            val pushback = PushbackReader(reader, PUSHBACK_BUFFER_CHARS)
            val prefix = StringBuilder()
            var next = pushback.read()
            while (next >= 0 && (next.toChar().isWhitespace() || next.toChar() == '\uFEFF')) {
                checkActive()
                next = pushback.read()
            }
            if (next >= 0) prefix.append(next.toChar())
            if (prefix.startsWith("[")) {
                while (prefix.length < SNIFF_PREFIX_CHARS && prefix.last() != ']') {
                    next = pushback.read()
                    if (next < 0 || next.toChar() == '\n' || next.toChar() == '\r') break
                    prefix.append(next.toChar())
                }
                if (next >= 0 && (next.toChar() == '\n' || next.toChar() == '\r')) prefix.append(next.toChar())
            }
            pushback.unread(prefix.toString().toCharArray())
            return ConfigImportContent(
                documentKind(prefix.toString(), schemes),
                pushback.buffered(), schemes, checkActive
            )
        } catch (e: Exception) {
            runCatching { reader.close() }
            throw e
        }
    }

    internal fun classifyLine(raw: String, profileSchemes: Set<String>): ConfigImportLine? {
        val line = raw.trim { it.isWhitespace() || it == '\uFEFF' }
        val scheme = ProtocolParserRegistry.schemeOf(line)
        if (scheme == null) {
            return bareSubscriptionUrl(line)?.let { ConfigImportLine(ConfigImportLineKind.SUBSCRIPTION, it) }
        }
        return when {
            scheme == AppConfig.HTTP || scheme == AppConfig.HTTPS ->
                ConfigImportLine(ConfigImportLineKind.SUBSCRIPTION, line)
            scheme in profileSchemes ->
                ConfigImportLine(ConfigImportLineKind.PROFILE, scheme + line.substring(scheme.length))
            else -> null
        }
    }

    private fun documentKind(prefix: String, profileSchemes: Set<String>): ConfigImportKind = when {
        prefix.isEmpty() -> ConfigImportKind.EMPTY
        prefix.startsWith('{') -> ConfigImportKind.JSON
        prefix.startsWith("[Interface]") -> ConfigImportKind.WIREGUARD
        prefix.startsWith('[') && classifyLine(prefix, profileSchemes) == null -> ConfigImportKind.JSON
        else -> ConfigImportKind.LINKS
    }

    /** Validate Base64 incrementally, without readLine() allocating the entire encoded blob. */
    private fun sniff(reader: Reader, profileSchemes: Set<String>, checkActive: () -> Unit): ConfigImportKind {
        val buffer = CharArray(DEFAULT_BUFFER_SIZE)
        val prefix = StringBuilder()
        var firstContent = true
        var bracketDocument = false
        var base64 = true
        var payload = 0L
        var padding = 0
        while (true) {
            checkActive()
            val size = reader.read(buffer)
            if (size < 0) break
            for (index in 0 until size) {
                val char = buffer[index]
                if (char == '\uFEFF' || char.isWhitespace()) {
                    if (char == '\n' || char == '\r') {
                        if (classifyLine(prefix.toString(), profileSchemes) != null) return ConfigImportKind.LINKS
                        if (bracketDocument) return documentKind(prefix.toString(), profileSchemes)
                        prefix.setLength(0)
                    } else if (prefix.isNotEmpty() && prefix.length < SNIFF_PREFIX_CHARS) {
                        prefix.append(char)
                    }
                    continue
                }
                if (firstContent) {
                    firstContent = false
                    if (char == '{') return ConfigImportKind.JSON
                    bracketDocument = char == '['
                }
                if (base64) {
                    when {
                        char == '=' -> {
                            padding++
                            if (padding > 2) base64 = false
                        }
                        padding == 0 && isBase64Char(char) -> payload++
                        else -> base64 = false
                    }
                }
                if (prefix.length < SNIFF_PREFIX_CHARS) {
                    prefix.append(char)
                    // A recognizable header/authority is enough; never scan its entire payload.
                    if (!base64 && (char == '/' || char == '?' || char == '#')
                        && classifyLine(prefix.toString(), profileSchemes) != null
                    ) {
                        return ConfigImportKind.LINKS
                    }
                }
                // '[' is also the first character of a schemeless IPv6 URL. Wait for the rest of
                // the line before deciding whether this is a JSON array or an IPv6 subscription.
                if (bracketDocument && prefix.length == SNIFF_PREFIX_CHARS) {
                    return documentKind(prefix.toString(), profileSchemes)
                }
            }
        }
        if (classifyLine(prefix.toString(), profileSchemes) != null) return ConfigImportKind.LINKS
        if (bracketDocument) return documentKind(prefix.toString(), profileSchemes)
        return if (base64 && payload > 0 && payload % 4 != 1L && (padding == 0 || (payload + padding) % 4 == 0L)) {
            ConfigImportKind.BASE64
        } else {
            ConfigImportKind.EMPTY
        }
    }

    private fun isBase64Char(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "+/-_"

    /**
     * Only an explicit domain or IP is unambiguous enough to supply a missing scheme. Default
     * to HTTPS and preserve the raw path/query/fragment rather than serializing the parsed URL.
     * Schemeless userinfo is deliberately rejected because it is ambiguous with email/mailto;
     * credential-bearing URLs must include an explicit HTTP(S) header.
     */
    private fun bareSubscriptionUrl(text: String): String? {
        if (text.isEmpty() || '\\' in text) return null
        val address = text.removePrefix("//")
        val authority = address.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.any(Char::isWhitespace) || '@' in authority) return null
        if ('.' !in authority && !authority.startsWith('[')) return null
        // A host-only token such as "US.Node" is more likely text than a subscription URL. A
        // path, query, or explicit port is required before a missing scheme is synthesized.
        if ('/' !in address && '?' !in address && !hasExplicitPort(authority)) return null

        val url = AppConfig.HTTPS + address
        val parsed = Utils.fixIllegalUrl(url).toHttpUrlOrNull() ?: return null
        val host = parsed.host.removeSuffix(".")
        val isDomain = host.length <= 253 && '.' in host && host.substringAfterLast('.').any(Char::isLetter)
            && host.split('.').all { label ->
                label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit()
                    && label.all { it.isLetterOrDigit() || it == '-' }
            }
        if (!isDomain && !Utils.isPureIpAddress(host)) return null
        return url
    }

    private fun hasExplicitPort(authority: String): Boolean {
        if (authority.startsWith('[')) {
            val closing = authority.indexOf(']')
            return closing >= 0 && authority.substring(closing + 1).startsWith(":")
        }
        val colon = authority.lastIndexOf(':')
        return colon > 0 && authority.substring(colon + 1).all(Char::isDigit)
    }
}

/** Converts ASCII Base64 text to bytes incrementally, normalizing URL-safe alphabet and whitespace. */
private class Base64TextInputStream(private val reader: Reader) : InputStream() {
    private val buffer = CharArray(DEFAULT_BUFFER_SIZE)
    private val singleByte = ByteArray(1)
    private var position = 0
    private var size = 0

    /** The source must contain ASCII Base64 text; non-ASCII input is rejected instead of truncated. */
    override fun read(): Int = if (read(singleByte, 0, 1) < 0) -1 else singleByte[0].toInt() and 0xff

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var count = 0
        while (count < length) {
            if (position == size) {
                size = reader.read(buffer)
                position = 0
                if (size < 0) break
                if (size == 0) continue
            }
            val char = buffer[position++]
            if (char.isWhitespace() || char == '\uFEFF') continue
            if (char.code > 0x7f) throw java.io.IOException("Non-ASCII character in Base64 input")
            bytes[offset + count++] = when (char) {
                '-' -> '+'.code
                '_' -> '/'.code
                else -> char.code
            }.toByte()
        }
        return if (count == 0) -1 else count
    }

    override fun close() = reader.close()
}
