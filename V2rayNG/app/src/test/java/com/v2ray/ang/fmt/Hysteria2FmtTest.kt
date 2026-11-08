package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

class Hysteria2FmtTest {

    @Test
    fun geckoDefaultsTo512Through1200ForBothUriSchemes() {
        listOf(AppConfig.HYSTERIA2, AppConfig.HY2).forEach { scheme ->
            val profile = Hysteria2Fmt.parse("${scheme}auth@example.com:443?obfs=gecko&obfs-password=secret")

            assertEquals(EConfigType.HYSTERIA2, profile.configType)
            assertEquals(NetworkType.HYSTERIA.type, profile.network)
            assertEquals("gecko", profile.obfs)
            assertEquals("secret", profile.obfsPassword)
            assertEquals(512, profile.minPacketSize)
            assertEquals(1200, profile.maxPacketSize)
            assertEquals("512-1200", obfsMask(profile)?.settings?.packetSize)
        }
    }

    @Test
    fun geckoCustomPacketSizesReachTheSerializedSalamanderMask() {
        val profile = parse("obfs=gecko&obfs-password=secret&minPacketSize=600&maxPacketSize=1100")
        val mask = checkNotNull(obfsMask(profile))
        val json = checkNotNull(JsonUtil.parseString(JsonUtil.toJson(mask)))

        assertEquals(600, profile.minPacketSize)
        assertEquals(1100, profile.maxPacketSize)
        assertEquals("salamander", json.get("type").asString)
        assertEquals("secret", json.getAsJsonObject("settings").get("password").asString)
        assertEquals("600-1100", json.getAsJsonObject("settings").get("packetSize").asString)
    }

    @Test
    fun missingGeckoRangeEndpointsDefaultIndependently() {
        val minOnly = parse("obfs=gecko&obfs-password=secret&minPacketSize=700")
        val maxOnly = parse("obfs=gecko&obfs-password=secret&maxPacketSize=1000")

        assertEquals(700, minOnly.minPacketSize)
        assertEquals(1200, minOnly.maxPacketSize)
        assertEquals("700-1200", obfsMask(minOnly)?.settings?.packetSize)
        assertEquals(512, maxOnly.minPacketSize)
        assertEquals(1000, maxOnly.maxPacketSize)
        assertEquals("512-1000", obfsMask(maxOnly)?.settings?.packetSize)
    }

    @Test
    fun geckoUriRoundTripPreservesDefaultAndCustomRanges() {
        listOf("", "&minPacketSize=600&maxPacketSize=1100").forEach { sizes ->
            val original = Hysteria2Fmt.parse(
                "${AppConfig.HYSTERIA2}auth%3Apass@example.com:443" +
                    "?obfs=gecko&obfs-password=secret%26%2B%3D%20value" +
                    "$sizes&sni=tls.example.com&mport=5000-6000&pinSHA256=pin#Gecko%20node"
            )
            val exported = shareUri(original)
            val query = Hysteria2Fmt.getQueryParam(URI(exported))
            val restored = Hysteria2Fmt.parse(exported)

            assertEquals("gecko", query["obfs"])
            assertEquals(original.minPacketSize.toString(), query["minPacketSize"])
            assertEquals(original.maxPacketSize.toString(), query["maxPacketSize"])
            assertEquals(original.obfs, restored.obfs)
            assertEquals(original.obfsPassword, restored.obfsPassword)
            assertEquals(original.minPacketSize, restored.minPacketSize)
            assertEquals(original.maxPacketSize, restored.maxPacketSize)
            assertEquals(original.password, restored.password)
            assertEquals(original.server, restored.server)
            assertEquals(original.serverPort, restored.serverPort)
            assertEquals(original.remarks, restored.remarks)
            assertEquals(original.sni, restored.sni)
            assertEquals(original.portHopping, restored.portHopping)
            assertEquals(original.pinnedCA256, restored.pinnedCA256)
            assertEquals(NetworkType.HYSTERIA.type, restored.network)
            assertEquals(obfsMask(original), obfsMask(restored))
        }
    }

    @Test
    fun noneAndMissingObfsDoNotEnableObfuscationFromAPassword() {
        listOf("", "&obfs=", "&obfs=none", "&obfs=NONE").forEach { obfs ->
            val profile = parse("obfs-password=secret$obfs&minPacketSize=600&maxPacketSize=1100")
            val query = Hysteria2Fmt.getQueryParam(URI(shareUri(profile)))

            assertNull(profile.obfs)
            assertNull(obfsMask(profile))
            assertFalse(query.containsKey("obfs"))
            assertFalse(query.containsKey("obfs-password"))
            assertFalse(query.containsKey("minPacketSize"))
            assertFalse(query.containsKey("maxPacketSize"))
        }
    }

    @Test
    fun disabledProfileObfsIsNeitherExportedNorEmittedAsAMask() {
        listOf(null, "", "none", "NONE").forEach { obfs ->
            val profile = parse("obfs=gecko&obfs-password=secret").copy(obfs = obfs)
            val query = Hysteria2Fmt.getQueryParam(URI(shareUri(profile)))

            assertNull(obfsMask(profile))
            assertFalse(query.containsKey("obfs"))
            assertFalse(query.containsKey("obfs-password"))
            assertFalse(query.containsKey("minPacketSize"))
            assertFalse(query.containsKey("maxPacketSize"))
        }
    }

    @Test
    fun salamanderNeverSetsPacketSizeEvenWithStoredGeckoSizes() {
        listOf("", "&minPacketSize=600&maxPacketSize=1100").forEach { sizes ->
            val profile = parse("obfs=salamander&obfs-password=secret$sizes")
            val mask = checkNotNull(obfsMask(profile))
            val query = Hysteria2Fmt.getQueryParam(URI(shareUri(profile)))
            val json = checkNotNull(JsonUtil.parseString(JsonUtil.toJson(mask)))

            assertEquals("salamander", profile.obfs)
            assertEquals("salamander", mask.type)
            assertEquals("secret", mask.settings?.password)
            assertNull(mask.settings?.packetSize)
            assertFalse(json.getAsJsonObject("settings").has("packetSize"))
            assertEquals("salamander", query["obfs"])
            assertEquals("secret", query["obfs-password"])
            assertFalse(query.containsKey("minPacketSize"))
            assertFalse(query.containsKey("maxPacketSize"))
        }
        val profile = parse("obfs=salamander&obfs-password=secret")
        assertNull(profile.minPacketSize)
        assertNull(profile.maxPacketSize)
    }

    @Test
    fun geckoProfileWithoutExplicitSizesStillGeneratesPadding() {
        val profile = parse("obfs=gecko&obfs-password=secret").copy(
            minPacketSize = null,
            maxPacketSize = null
        )
        val query = Hysteria2Fmt.getQueryParam(URI(shareUri(profile)))

        assertEquals("gecko", query["obfs"])
        assertFalse(query.containsKey("minPacketSize"))
        assertFalse(query.containsKey("maxPacketSize"))
        assertEquals("512-1200", obfsMask(profile)?.settings?.packetSize)
    }

    @Test
    fun geckoExportWritesOnlyConfiguredRangeEndpoints() {
        val profile = parse("obfs=gecko&obfs-password=secret").copy(
            minPacketSize = 600,
            maxPacketSize = null
        )
        val query = Hysteria2Fmt.getQueryParam(URI(shareUri(profile)))

        assertEquals("600", query["minPacketSize"])
        assertFalse(query.containsKey("maxPacketSize"))
    }

    @Test
    fun genericTransportQueryCannotReplaceHysteriaTransport() {
        val profile = parse("obfs=gecko&obfs-password=secret&type=tcp")

        assertEquals(NetworkType.HYSTERIA.type, profile.network)
        assertEquals("512-1200", obfsMask(profile)?.settings?.packetSize)
    }

    private fun parse(query: String): ProfileItem =
        Hysteria2Fmt.parse("${AppConfig.HYSTERIA2}auth@example.com:443?$query")

    private fun shareUri(profile: ProfileItem): String = AppConfig.HYSTERIA2 + Hysteria2Fmt.toUri(profile)

    private fun obfsMask(profile: ProfileItem): OutboundBean.StreamSettingsBean.FinalMaskBean.MaskBean? {
        val stream = OutboundBean.StreamSettingsBean()
        CoreOutboundBuilder.populateTransportSettings(stream, profile)
        val finalMask = stream.finalmask as OutboundBean.StreamSettingsBean.FinalMaskBean
        return finalMask.udp?.singleOrNull { it.type == "salamander" }
    }
}
