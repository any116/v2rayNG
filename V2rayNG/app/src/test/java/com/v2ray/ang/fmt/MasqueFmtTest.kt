package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MasqueFmtTest {
    @Test
    fun shareLinkRoundTripPreservesMasqueFields() {
        val profile = ProfileItem(
            configType = EConfigType.MASQUE,
            remarks = "masque node",
            server = "example.com",
            serverPort = "443",
            username = "user",
            password = "pass",
            network = NetworkType.MASQUE.type,
            host = "gateway.example.com",
            path = "/.well-known/masque/ip/*/*/",
            masqueHeaders = "{\"X-Token\":\"value\"}",
            security = AppConfig.TLS,
            alpn = "h3,h2",
            fingerPrint = "chrome",
            echConfigList = "ech-config",
            verifyPeerCertByName = "gateway.example.com",
            pinnedCA256 = "sha256//pin",
            remoteDNS = "1.1.1.1,2606:4700:4700::1111",
        )

        val parsed = MasqueFmt.parse(MasqueFmt.toUri(profile))

        assertEquals(profile.remarks, parsed?.remarks)
        assertEquals(profile.server, parsed?.server)
        assertEquals(profile.serverPort, parsed?.serverPort)
        assertEquals(profile.username, parsed?.username)
        assertEquals(profile.password, parsed?.password)
        assertEquals(profile.network, parsed?.network)
        assertEquals(profile.host, parsed?.host)
        assertEquals(profile.path, parsed?.path)
        assertEquals(profile.masqueHeaders, parsed?.masqueHeaders)
        assertEquals(profile.security, parsed?.security)
        assertEquals(profile.alpn, parsed?.alpn)
        assertEquals(profile.fingerPrint, parsed?.fingerPrint)
        assertEquals(profile.echConfigList, parsed?.echConfigList)
        assertEquals(profile.verifyPeerCertByName, parsed?.verifyPeerCertByName)
        assertEquals(profile.pinnedCA256, parsed?.pinnedCA256)
        assertEquals(profile.remoteDNS, parsed?.remoteDNS)
    }

    @Test
    fun warpShareLinkRoundTripPreservesDeviceCredentials() {
        val profile = ProfileItem.create(EConfigType.MASQUE).apply {
            remarks = "warp masque"
            server = "162.159.198.2"
            serverPort = "443"
            network = NetworkType.MASQUE.type
            security = AppConfig.TLS
            masqueWarp = true
            masqueWarpPrivateKey = "private-key"
            masqueWarpPublicKey = "public-key"
            masqueWarpAddress = "172.16.0.2/32,2606:4700:110:8a36::2/128"
            sni = AppConfig.MASQUE_WARP_SNI
        }

        val parsed = MasqueFmt.parse(MasqueFmt.toUri(profile))

        assertEquals(true, parsed?.masqueWarp)
        assertEquals(profile.masqueWarpPrivateKey, parsed?.masqueWarpPrivateKey)
        assertEquals(profile.masqueWarpPublicKey, parsed?.masqueWarpPublicKey)
        assertEquals(profile.masqueWarpAddress, parsed?.masqueWarpAddress)
        assertEquals(null, parsed?.username)
        assertEquals(null, parsed?.password)
        assertEquals(profile.sni, parsed?.sni)
    }
}
