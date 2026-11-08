package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreOutboundBuilderTest {

    @Test
    fun templateTransportKeepsFolderSecretsAndObject() {
        val template = """
            {"put":{"url":"https://storage.example/put/{name}"},
             "get":{"url":"https://storage.example/get/{name}"},
             "delete":{"url":"https://storage.example/delete/{name}"},
             "list":{"url":"https://storage.example/list/{prefix}","namesRegex":"([^<]+)"}}
        """.trimIndent()
        val profile = xdriveProfile().apply {
            service = AppConfig.XDRIVE_SERVICE_TEMPLATE
            remoteFolder = "folder"
            secrets = "user\npass"
            this.template = template
        }
        val stream = OutboundBean.StreamSettingsBean()

        CoreOutboundBuilder.populateTransportSettings(stream, profile)

        val settings = checkNotNull(stream.xdriveSettings)
        assertEquals("folder", settings.remoteFolder)
        assertEquals(AppConfig.XDRIVE_SERVICE_TEMPLATE, settings.service)
        assertEquals(listOf("user", "pass"), settings.secrets)
        assertEquals(JsonUtil.parseString(template).toString(), settings.template.toString())
    }

    @Test
    fun s3TransportKeepsSettingsAsAnObjectAndCredentialOrder() {
        val profile = s3Profile()
        val outbound = checkNotNull(CoreOutboundBuilder.convert(profile))
        val stream = checkNotNull(outbound.streamSettings)
        val settings = checkNotNull(stream.xdriveSettings)
        val json = checkNotNull(JsonUtil.parseString(JsonUtil.toJson(settings)))

        assertEquals(AppConfig.XDRIVE_SERVICE_S3, settings.service)
        assertEquals("streams/client/", settings.remoteFolder)
        assertEquals(listOf("access", "secret", "token"), settings.secrets)
        assertEquals(JsonUtil.parseString(profile.s3), json.getAsJsonObject("s3"))
        assertNull(settings.template)
        assertEquals(true, outbound.mux?.enabled)
    }

    @Test
    fun s3SettingsAreNotEmittedForOtherServices() {
        val profile = s3Profile().apply { service = AppConfig.XDRIVE_SERVICE_LOCAL }
        val stream = OutboundBean.StreamSettingsBean()

        CoreOutboundBuilder.populateTransportSettings(stream, profile)

        assertNull(stream.xdriveSettings?.s3)
        assertNull(stream.xdriveSettings?.secrets)
    }

    @Test
    fun invalidS3ProfilesAreNotConvertedIntoCoreOutbounds() {
        assertNotNull(CoreOutboundBuilder.convert(s3Profile().apply { secrets = "access\nsecret" }))
        assertNull(CoreOutboundBuilder.convert(s3Profile().apply { s3 = null }))
        assertNull(CoreOutboundBuilder.convert(s3Profile().apply { s3 = "{}" }))
        assertNull(CoreOutboundBuilder.convert(s3Profile().apply { secrets = "access\n\ntoken" }))
        assertNull(CoreOutboundBuilder.convert(s3Profile().apply { remoteFolder = "/streams" }))
    }

    private fun s3Profile() = xdriveProfile().apply {
        service = AppConfig.XDRIVE_SERVICE_S3
        remoteFolder = "streams/client/"
        secrets = "access\nsecret\ntoken"
        s3 = """{"endpoint":"https://s3.example.com","region":"us-east-1","bucket":"bucket","pathStyle":true}"""
    }

    @Test
    fun invalidXdriveProfilesAreNotConvertedIntoCoreOutbounds() {
        assertNull(CoreOutboundBuilder.convert(xdriveProfile()))
        assertNull(CoreOutboundBuilder.convert(xdriveProfile().apply { flow = "xtls-rprx-vision" }))
        assertNull(CoreOutboundBuilder.convert(xdriveProfile().apply { method = "none" }))
        assertNull(CoreOutboundBuilder.convert(xdriveProfile().copy(configType = EConfigType.VMESS)))
    }

    @Test
    fun tlsOutboundDefaultsToSystemCertificateRoots() {
        val profile = ProfileItem.create(EConfigType.VMESS).apply {
            server = "example.com"
            serverPort = "443"
            security = AppConfig.TLS
            finalMask = "{}"
        }
        val stream = OutboundBean.StreamSettingsBean()

        CoreOutboundBuilder.populateTlsSettings(stream, profile, "example.com")

        assertEquals(true, stream.tlsSettings?.useSystemCA)
        assertEquals(false, stream.tlsSettings?.disableSystemRoot)
    }

    @Test
    fun masqueWarpOutboundUsesWarpSettingsAndSkipsBasicAuthentication() {
        val profile = ProfileItem.create(EConfigType.MASQUE).apply {
            server = "162.159.198.2"
            serverPort = "443"
            network = NetworkType.MASQUE.type
            security = AppConfig.TLS
            masqueWarp = true
            masqueWarpPrivateKey = "private-key"
            masqueWarpPublicKey = "public-key"
            masqueWarpAddress = "172.16.0.2/32,2606:4700:110:8a36::2/128"
            username = "ignored-user"
            password = "ignored-password"
        }

        val outbound = checkNotNull(CoreOutboundBuilder.convert(profile))
        val stream = checkNotNull(outbound.streamSettings)
        val masque = checkNotNull(stream.masqueSettings)
        val warp = checkNotNull(masque.warp)

        assertEquals("private-key", warp.privateKey)
        assertEquals("public-key", warp.publicKey)
        assertEquals(
            listOf("172.16.0.2/32", "2606:4700:110:8a36::2/128"),
            warp.address
        )
        assertNull(masque.headers)
        assertNull(masque.path)
        assertEquals(AppConfig.MASQUE_WARP_SNI, stream.tlsSettings?.serverName)
        assertEquals(false, stream.tlsSettings?.allowInsecure)
        assertNotNull(stream.tlsSettings)
        assertTrue(JsonUtil.toJson(outbound).contains("\"warp\""))
    }

    @Test
    fun hysteriaPortHoppingUsesUdpMaskInsteadOfQuicParams() {
        val profile = hysteriaProfile().apply {
            portHopping = "443,5000-6000"
            portHoppingInterval = "10-20"
            bandwidthUp = "10 mbps"
            bandwidthDown = "20 mbps"
        }
        val stream = OutboundBean.StreamSettingsBean()

        CoreOutboundBuilder.populateTransportSettings(stream, profile)

        val json = checkNotNull(JsonUtil.parseString(JsonUtil.toJson(stream)))
        val finalmask = json.getAsJsonObject("finalmask")
        val masks = finalmask.getAsJsonArray("udp")
        assertEquals(1, masks.size())
        assertEquals(
            JsonUtil.parseString(
                """
                    {"type":"udphop","settings":{"mode":"intervalLocal,intervalRemote",
                     "remotePorts":"443,5000-6000","interval":"10-20"}}
                """.trimIndent()
            ),
            masks[0]
        )
        val quicParams = finalmask.getAsJsonObject("quicParams")
        assertEquals("brutal", quicParams.get("congestion").asString)
        assertEquals("10 mbps", quicParams.get("brutalUp").asString)
        assertEquals("20 mbps", quicParams.get("brutalDown").asString)
        assertNull(quicParams.get("udpHop"))
        assertEquals(2, stream.hysteriaSettings?.version)
        assertEquals("auth", stream.hysteriaSettings?.auth)
    }

    @Test
    fun hysteriaPortHoppingPrecedesSalamanderMask() {
        val profile = hysteriaProfile().apply {
            portHopping = "5000-6000"
            obfsPassword = "obfs-password"
        }
        val stream = OutboundBean.StreamSettingsBean()

        CoreOutboundBuilder.populateTransportSettings(stream, profile)

        val json = checkNotNull(JsonUtil.parseString(JsonUtil.toJson(stream)))
        val masks = json.getAsJsonObject("finalmask").getAsJsonArray("udp")
        assertEquals(listOf("udphop", "salamander"), masks.map { it.asJsonObject.get("type").asString })
        assertEquals("30", masks[0].asJsonObject.getAsJsonObject("settings").get("interval").asString)
        assertEquals("obfs-password", masks[1].asJsonObject.getAsJsonObject("settings").get("password").asString)
    }

    @Test
    fun hysteriaPortHoppingKeepsIntervalNormalization() {
        val intervals = listOf(
            null to "30",
            "" to "30",
            "  " to "30",
            "4" to "30",
            "5" to "5",
            " 10 " to "10",
            "invalid" to "30",
            "1-3" to "5-5",
            "20-10" to "20-20",
            "10 - 20" to "10-20",
            "x-20" to "30",
            "1-2-3" to "30"
        )
        intervals.forEach { (input, expected) ->
            val profile = hysteriaProfile().apply {
                portHopping = "5000-6000"
                portHoppingInterval = input
            }
            val stream = OutboundBean.StreamSettingsBean()

            CoreOutboundBuilder.populateTransportSettings(stream, profile)

            val json = checkNotNull(JsonUtil.parseString(JsonUtil.toJson(stream)))
            val hop = json.getAsJsonObject("finalmask").getAsJsonArray("udp")[0].asJsonObject
            assertEquals(expected, hop.getAsJsonObject("settings").get("interval").asString, "interval=$input")
        }
    }

    @Test
    fun hysteriaWithoutPortHoppingKeepsOptionalSalamanderMask() {
        listOf(null, "").forEach { ports ->
            listOf(null, "obfs-password").forEach { obfs ->
                val profile = hysteriaProfile().apply {
                    portHopping = ports
                    portHoppingInterval = "10"
                    obfsPassword = obfs
                }
                val stream = OutboundBean.StreamSettingsBean()

                CoreOutboundBuilder.populateTransportSettings(stream, profile)

                val json = checkNotNull(JsonUtil.parseString(JsonUtil.toJson(stream)))
                val finalmask = json.getAsJsonObject("finalmask")
                assertNull(finalmask.getAsJsonObject("quicParams").get("udpHop"))
                if (obfs == null) {
                    assertNull(finalmask.get("udp"))
                } else {
                    val masks = finalmask.getAsJsonArray("udp")
                    assertEquals(1, masks.size())
                    assertEquals("salamander", masks[0].asJsonObject.get("type").asString)
                }
            }
        }
    }

    @Test
    fun customFinalMaskStillOverridesGeneratedHysteriaMasks() {
        val custom = """
            {"udp":[{"type":"udphop","settings":{"mode":"intervalRemote",
             "remotePorts":"8443","interval":"60"}}]}
        """.trimIndent()
        val profile = hysteriaProfile().apply {
            portHopping = "5000-6000"
            obfsPassword = "obfs-password"
            finalMask = custom
        }
        val stream = OutboundBean.StreamSettingsBean()

        CoreOutboundBuilder.populateTransportSettings(stream, profile)

        val json = checkNotNull(JsonUtil.parseString(JsonUtil.toJson(stream)))
        assertEquals(JsonUtil.parseString(custom), json.getAsJsonObject("finalmask"))
    }

    private fun hysteriaProfile() = ProfileItem(configType = EConfigType.HYSTERIA2).apply {
        network = NetworkType.HYSTERIA.type
        password = "auth"
    }

    private fun xdriveProfile() = ProfileItem.create(EConfigType.VLESS).apply {
        remarks = "XDRIVE"
        server = "xdrive.example.com"
        serverPort = "443"
        password = "uuid"
        method = "native-encryption"
        network = NetworkType.XDRIVE.type
        service = AppConfig.XDRIVE_SERVICE_TEMPLATE
        template = "{}"
    }
}
