package com.v2ray.ang.ui.server

import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireguardFormTest {

    private val validForm = ServerForm(remarks = "WireGuard", address = "example.com")

    @Test
    fun editorRoundTripPreservesNewOptions() {
        val profile = ProfileItem.create(EConfigType.WIREGUARD).apply {
            remoteDNS = "9.9.9.9,2620:fe::fe"
        }

        val saved = ServerForm.from(profile).toProfileItem(profile, EConfigType.WIREGUARD)

        assertEquals(profile.remoteDNS, saved.remoteDNS)
    }

    @Test
    fun acceptsBlankDefaultsAndDualStackIpLists() {
        assertTrue(ServerValidator.validateForm(EConfigType.WIREGUARD, validForm).isEmpty())
        assertTrue(
            ServerValidator.validateForm(
                EConfigType.WIREGUARD,
                validForm.copy(remoteDNS = "9.9.9.9, 2620:fe::fe")
            ).isEmpty()
        )
    }

    @Test
    fun remoteDnsRejectsDomainsUrlsPortsAndCidr() {
        listOf("dns.example.com", "https://dns.example.com/dns-query", "9.9.9.9:53", "9.9.9.9/32", "[::1]", ",")
            .forEach { value ->
                assertTrue(
                    ServerField.REMOTE_DNS in ServerValidator.validateForm(
                        EConfigType.WIREGUARD, validForm.copy(remoteDNS = value)
                    ),
                    value
                )
            }
    }

}
