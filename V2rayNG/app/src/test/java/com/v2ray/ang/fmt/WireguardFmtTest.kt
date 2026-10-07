package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WireguardFmtTest {

    @Test
    fun shareLinkRoundTripPreservesTunnelAndPeerOptions() {
        val profile = ProfileItem.create(EConfigType.WIREGUARD).apply {
            remarks = "WireGuard"
            server = "2001:db8::1"
            serverPort = "51820"
            secretKey = "private/key+value="
            publicKey = "public/key+value="
            remoteDNS = "9.9.9.9,2606:4700:4700::1111"
        }

        val parsed = WireguardFmt.parse(AppConfig.WIREGUARD + WireguardFmt.toUri(profile))

        assertNotNull(parsed)
        assertEquals(profile.secretKey, parsed?.secretKey)
        assertEquals(profile.publicKey, parsed?.publicKey)
        assertEquals(profile.remoteDNS, parsed?.remoteDNS)
    }

    @Test
    fun acceptsDnsQueryName() {
        val parsed = WireguardFmt.parse(
            "wireguard://private@example.com:51820?publickey=public&dns=9.9.9.9"
        )!!

        assertEquals("9.9.9.9", parsed.remoteDNS)
        val reparsed = WireguardFmt.parse(AppConfig.WIREGUARD + WireguardFmt.toUri(parsed))!!
        assertEquals("9.9.9.9", reparsed.remoteDNS)
    }

    @Test
    fun oldLinksLeaveNewOptionsUnset() {
        val parsed = WireguardFmt.parse("wireguard://private@example.com:51820?publickey=public")!!

        assertNull(parsed.remoteDNS)
    }

    @Test
    fun confImportUsesInterfaceDnsAndIpv6Endpoint() {
        val parsed = WireguardFmt.parseWireguardConfFile(
            """
            [Interface]
            PrivateKey = private=
            Address = 10.0.0.2/32, fd00::2/128
            DNS = 9.9.9.9, 2620:fe::fe
            [Peer]
            PublicKey = public=
            Endpoint = [2001:db8::1]:51820
            """.trimIndent()
        )

        assertEquals("2001:db8::1", parsed.server)
        assertEquals("51820", parsed.serverPort)
        assertEquals("9.9.9.9, 2620:fe::fe", parsed.remoteDNS)
    }

    @Test
    fun confRemoteDnsOverridesDns() {
        val parsed = WireguardFmt.parseWireguardConfFile(
            """
            [Interface]
            DNS = 1.1.1.1
            RemoteDNS = 8.8.8.8
            [Peer]
            Endpoint = example.com:51820
            """.trimIndent()
        )

        assertEquals("8.8.8.8", parsed.remoteDNS)
    }
}
