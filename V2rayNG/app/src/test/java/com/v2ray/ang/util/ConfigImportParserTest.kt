package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.ConfigImportSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.Base64

class ConfigImportParserTest {

    private val profileSchemes = setOf(
        AppConfig.VMESS,
        AppConfig.SHADOWSOCKS,
        AppConfig.SOCKS,
        AppConfig.SOCKS4,
        AppConfig.SOCKS5,
        AppConfig.VLESS,
        AppConfig.TROJAN,
        AppConfig.WIREGUARD,
        AppConfig.HYSTERIA2,
        AppConfig.HY2,
        AppConfig.V2RAYNFMTS
    )

    @Test
    fun subscriptionUrlsBypassBase64Decoding() {
        val urls = listOf(
            "https://example.com/sub?token=a%2Fb#Group",
            "http://192.168.1.1/sub",
            "HTTPS://Example.com/Sub"
        )

        assertEquals(ImportSnapshot(subscriptionUrls = urls), parsePlain(urls.joinToString("\n")))
    }

    @Test
    fun schemelessDomainsAndIpAddressesDefaultToHttps() {
        val urls = listOf(
            "example.com/sub?token=a%2Fb#Group",
            "example.com/sub?redirect=https://other.example.com/path",
            "www.example.com/",
            "192.168.1.1:8080/sub",
            "//example.com/sub",
            "[2001:db8::1]:8443/sub",
            "例子.测试/订阅"
        )

        urls.forEach { url ->
            val expected = AppConfig.HTTPS + url.removePrefix("//")
            assertEquals(ImportSnapshot(subscriptionUrls = listOf(expected)), parsePlain(url))
        }
    }

    @Test
    fun schemelessUrlsAreDeduplicatedWithExplicitHttpsLinks() {
        val url = "https://example.com/sub"
        val link = "trojan://password@example.com:443#Node"
        val text = "example.com/sub\n$link\n$url\n//example.com/sub"

        assertEquals(
            ImportSnapshot(profileLinks = listOf(link), subscriptionUrls = listOf(url)),
            parsePlain(text)
        )
    }

    @Test
    fun malformedSchemelessAddressesAreNotImported() {
        listOf(
            "-example.com/sub",
            "example..com/sub",
            "999.999.999.999/sub",
            "example.com:99999/sub",
            "example.com:abc/sub",
            "example.com /sub",
            "mailto:user@example.com",
            "ftp://example.com/sub",
            "com.example://ignored"
        ).forEach { text -> assertEquals(ImportSnapshot(), parsePlain(text)) }
    }

    @Test
    fun userinfoNeedsAnExplicitHttpScheme() {
        listOf("user@example.com/sub", "user:password@example.com/sub", "//user@example.com/sub")
            .forEach { text -> assertEquals(ImportSnapshot(), parsePlain(text)) }
        val explicit = "https://user:password@example.com/sub"
        assertEquals(ImportSnapshot(subscriptionUrls = listOf(explicit)), parsePlain(explicit))
    }

    @Test
    fun hostOnlyTokensAreNotTreatedAsSubscriptionUrls() {
        listOf("www.example.com", "US.Node", "config.txt", "v2rayN.com")
            .forEach { assertEquals(ImportSnapshot(), parsePlain(it)) }
    }

    @Test
    fun bareRegistryKeysAlsoWorkForContentClassification() {
        ConfigImportParser.parse("VMESS://AbCdEf==", setOf("vmess"), decodeBase64 = { error("Unexpected decode") })
            .use { content -> assertEquals("vmess://AbCdEf==", content.lines().single().text) }
    }

    @Test
    fun everySupportedProtocolBypassesWholeContentDecoding() {
        profileSchemes.forEach { scheme ->
            val link = "${scheme}AbCdEf=="
            assertEquals(ImportSnapshot(profileLinks = listOf(link)), parsePlain(link))
        }
    }

    @Test
    fun protocolSchemeIsCaseInsensitiveWithoutChangingPayload() {
        val link = "VMESS://AbCdEf=="

        assertEquals(
            ImportSnapshot(profileLinks = listOf("vmess://AbCdEf==")),
            parsePlain(link)
        )
    }

    @Test
    fun mixedLinksAreTrimmedAndDeduplicatedWithinEachType() {
        val url = "https://example.com/sub"
        val link = "vless://id@example.com:443?encryption=none#Node"
        val text = "\uFEFF  $url \r\n\t$link\n\n$url\n $link \nunknown://ignored\n"

        assertEquals(
            ImportSnapshot(profileLinks = listOf(link), subscriptionUrls = listOf(url)),
            parsePlain(text)
        )
    }

    @Test
    fun jsonAndWireguardConfigsBypassBase64Decoding() {
        val configs = listOf(
            """{"inbounds": [], "outbounds": [], "routing": {}}""",
            """[{"inbounds": [], "outbounds": [], "routing": {}}]""",
            "[Interface]\nPrivateKey = key=\n[Peer]\nEndpoint = example.com:443"
        )

        configs.forEach { config ->
            assertEquals(ImportSnapshot(customConfig = config), parsePlain(" \n$config\n "))
        }
    }

    @Test
    fun linksInsideCustomConfigAreNotImportedSeparately() {
        val config = """
            {
                "inbounds": [],
                "outbounds": [],
                "routing": {},
                "remarks": "https://example.com/sub"
            }
        """.trimIndent()

        assertEquals(ImportSnapshot(customConfig = config), parsePlain(config))
    }

    @Test
    fun documentContentCannotBeConsumedAsALinkBatch() {
        val config = """{"inbounds":[],"outbounds":[],"routing":{}}"""
        ConfigImportParser.parse(config, profileSchemes).use { content ->
            assertThrows(IllegalStateException::class.java) { content.lines().toList() }
            assertEquals(config, content.reader.readText())
        }
    }

    @Test
    fun base64IsOnlyASniffingStateAndCannotBeExposedAsPreparedContent() {
        StringReader("payload").buffered().use { reader ->
            assertThrows(IllegalArgumentException::class.java) {
                ConfigImportContent(ConfigImportKind.BASE64, reader, profileSchemes) {}
            }
        }
    }

    @Test
    fun subscriptionResponsesConsumeNodesWithoutFollowingNestedSubscriptionUrls() {
        val url = "https://example.com/sub"
        val link = "trojan://password@example.com:443#Node"
        val text = "$url\n$link\n$url\n$link"
        listOf(text, encode(text)).forEach { input ->
            ConfigImportParser.parse(input, profileSchemes, decodeBase64 = ::decode).use { content ->
                val lines = content.lines(ConfigImportSource.SUBSCRIPTION_RESPONSE).map { it.text }.toList()
                assertEquals(listOf(link), lines)
            }
        }
        ConfigImportParser.parse(text, profileSchemes).use { content ->
            assertEquals(listOf(url, link), content.lines(ConfigImportSource.USER_INPUT).map { it.text }.toList())
        }
        ConfigImportParser.parse(url, profileSchemes).use { content ->
            assertEquals(
                emptyList<ConfigImportLine>(),
                content.lines(ConfigImportSource.SUBSCRIPTION_RESPONSE).toList()
            )
        }
    }

    @Test
    fun independentSubscriptionUrlStreamsAreDeduplicatedInFirstSeenOrder() {
        val first = "https://example.com/one"
        val second = "https://example.com/two"
        assertEquals(
            listOf(first, second),
            sequenceOf(first, first, second, first).distinctImportText(key = { it }).toList()
        )
    }

    @Test
    fun base64BatchIsDecodedOnceAndThenSplitByType() {
        val url = "https://example.com/sub"
        val link = "trojan://password@example.com:443#Node"
        val encoded = encode("$url\n$link")
        var decodeCount = 0
        val result = snapshot(ConfigImportParser.parse(encoded, profileSchemes, decodeBase64 = {
            decodeCount++
            decode(it)
        }))

        assertEquals(1, decodeCount)
        assertEquals(ImportSnapshot(profileLinks = listOf(link), subscriptionUrls = listOf(url)), result)
    }

    @Test
    fun base64SchemelessUrlIsRecognizedAfterDecoding() {
        assertEquals(
            ImportSnapshot(subscriptionUrls = listOf("https://example.com/sub")),
            parseEncoded(encode("example.com/sub"))
        )
    }

    @Test
    fun wrappedAndUnpaddedBase64AreAccepted() {
        val link = "vmess://AbCdEf=="
        val encoded = encode(link)
        val variants = listOf(encoded, encoded.trimEnd('='), encoded.chunked(8).joinToString(" \r\n\t"))

        variants.forEach { text ->
            assertEquals(ImportSnapshot(profileLinks = listOf(link)), parseEncoded(text))
        }
    }

    @Test
    fun base64TextStreamKeepsEofAfterReturningAFinalPartialBuffer() {
        val link = "vmess://AbCdEf=="
        val encoded = encode(link)
        val variants = listOf(encoded, encoded.trimEnd('='), encoded.chunked(8).joinToString(" \r\n\t"))

        variants.forEach { text ->
            val expected = text.filterNot(Char::isWhitespace)
            val result = snapshot(ConfigImportParser.parse(text, profileSchemes, decodeBase64 = { input ->
                input.use {
                    val buffer = ByteArray(2048)
                    val count = it.read(buffer)
                    assertEquals(expected.length, count)
                    assertEquals(expected, String(buffer, 0, count, Charsets.US_ASCII))
                    assertEquals(-1, it.read(buffer))
                    assertEquals(-1, it.read())
                    assertEquals(-1, it.read(buffer, 0, 1))
                    assertEquals(0, it.read(buffer, 0, 0))
                    Base64.getDecoder().decode(buffer.copyOf(count)).inputStream()
                }
            }))

            assertEquals(ImportSnapshot(profileLinks = listOf(link)), result)
        }
    }

    @Test
    fun base64DecoderCanConsumeMultipleBuffersThroughEof() {
        val links = (0 until 200).map { "trojan://password@example.com:443#Node$it" }
        val encoded = encode(links.joinToString("\n"))
        val result = snapshot(ConfigImportParser.parse(encoded, profileSchemes, decodeBase64 = { input ->
            input.use {
                // Android's decoder reads blocks through EOF, unlike the JVM decoder's byte-by-byte reads.
                val bytes = it.readBytes()
                assertEquals(encoded, bytes.toString(Charsets.US_ASCII))
                assertEquals(-1, it.read(ByteArray(2048)))
                assertEquals(-1, it.read())
                Base64.getDecoder().decode(bytes).inputStream()
            }
        }))

        assertEquals(ImportSnapshot(profileLinks = links), result)
    }

    @Test
    fun urlSafeBase64IsAccepted() {
        val link = "trojan://password@example.com:443#🚀节点"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(link.toByteArray(Charsets.UTF_8))
        assertTrue(encoded.any { it == '-' || it == '_' })

        assertEquals(ImportSnapshot(profileLinks = listOf(link)), parseEncoded(encoded))
    }

    @Test
    fun base64CustomConfigsUseTheDecodedText() {
        val config = """{"inbounds": [], "outbounds": [], "routing": {}}"""

        assertEquals(ImportSnapshot(customConfig = config), parseEncoded(encode(config)))
    }

    @Test
    fun malformedOrUnknownContentDoesNotReachTheDecoder() {
        listOf(null, "", " \n ", "A", "ab=c", "a===", "ab==cd==", "Zm9v=", "invalid!", "unknown://data")
            .forEach { text -> assertEquals(ImportSnapshot(), parsePlain(text)) }
    }

    @Test
    fun decodedUnknownContentIsNotImportedOrDecodedRecursively() {
        var decodeCount = 0
        val result = snapshot(ConfigImportParser.parse(
            encode(encode("https://example.com/sub")), profileSchemes,
            decodeBase64 = {
                decodeCount++
                decode(it)
            }
        ))

        assertEquals(1, decodeCount)
        assertEquals(ImportSnapshot(), result)
        assertEquals(ImportSnapshot(), parseEncoded(encode("arbitrary text")))
    }

    @Test
    fun largeBase64SubscriptionIsDecodedLazilyAndClosedAfterEarlyExit() {
        val text = (0 until 10_000).joinToString("\n") { "trojan://password@example.com:443#Node$it" }
        var decodedBytes = 0
        var decoderClosed = false
        ConfigImportParser.parse(encode(text), profileSchemes, decodeBase64 = { input ->
            object : FilterInputStream(decode(input)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(bytes, offset, length)
                    if (count > 0) decodedBytes += count
                    return count
                }

                override fun close() {
                    decoderClosed = true
                    super.close()
                }
            }
        }).use { content ->
            assertEquals("trojan://password@example.com:443#Node0", content.lines().first().text)
            assertTrue(decodedBytes < text.toByteArray(Charsets.UTF_8).size)
        }
        assertTrue(decoderClosed)
    }

    @Test
    fun reopenableSourceReadersAreClosedOnEarlyExit() {
        var opened = 0
        var closed = 0
        val source = {
            opened++
            object : StringReader("trojan://password@example.com:443#First\nhttps://example.com/sub") {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        }

        ConfigImportParser.parse(source, profileSchemes).use { content ->
            assertEquals(ConfigImportLineKind.PROFILE, content.lines().first().kind)
        }
        assertEquals(opened, closed)
    }

    @Test
    fun oversizedUnknownLinesDoNotHideLaterProfiles() {
        val link = "trojan://password@example.com:443#Node"
        assertEquals(ImportSnapshot(profileLinks = listOf(link)), parsePlain("A".repeat(100_000) + "\n$link"))
    }

    @Test
    fun randomizedSupportedInputsKeepTheirDocumentKind() {
        val random = java.util.Random(7L)
        repeat(100) {
            val suffix = buildString {
                repeat(12) { append(('a'.code + random.nextInt(26)).toChar()) }
            }
            val link = "trojan://password@example.com:443#$suffix"
            parsePlain(link).also { snapshot ->
                assertEquals(ImportSnapshot(profileLinks = listOf(link)), snapshot)
            }
            val encoded = encode(link)
            assertEquals(ImportSnapshot(profileLinks = listOf(link)), parseEncoded(encoded))
        }
    }

    private data class ImportSnapshot(
        val profileLinks: List<String> = emptyList(),
        val subscriptionUrls: List<String> = emptyList(),
        val customConfig: String? = null
    )

    private fun snapshot(content: ConfigImportContent): ImportSnapshot = content.use {
        if (it.kind == ConfigImportKind.JSON || it.kind == ConfigImportKind.WIREGUARD) {
            ImportSnapshot(customConfig = it.reader.readText().trim())
        } else {
            val lines = it.lines().toList()
            ImportSnapshot(
                profileLinks = lines.filter { line -> line.kind == ConfigImportLineKind.PROFILE }
                    .map { line -> line.text },
                subscriptionUrls = lines.filter { line -> line.kind == ConfigImportLineKind.SUBSCRIPTION }
                    .map { line -> line.text }
            )
        }
    }

    private fun parsePlain(text: String?): ImportSnapshot = snapshot(
        ConfigImportParser.parse(
            text, profileSchemes,
            decodeBase64 = { error("Plain content must not be Base64-decoded") }
        )
    )

    private fun parseEncoded(text: String): ImportSnapshot =
        snapshot(ConfigImportParser.parse(text, profileSchemes, decodeBase64 = ::decode))

    private fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun decode(input: InputStream): InputStream = Base64.getDecoder().wrap(input)
}
