package com.v2ray.ang.ui.server

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MasqueFormTest {

    private val validForm = ServerForm(
        remarks = "MASQUE",
        address = "gateway.example.com",
        streamSecurity = AppConfig.TLS,
        masqueHeaders = "Authorization: Basic dXNlcjpwYXNz",
        host = "masque.example.com",
        path = "/.well-known/masque/ip/{target}/{ipproto}/",
        remoteDNS = "1.1.1.1,2606:4700:4700::1111",
    )

    @Test
    fun formRoundTripForcesMasqueTransportAndTls() {
        val initial = ProfileItem.create(EConfigType.MASQUE).apply {
            insecure = true
            security = "reality"
        }

        val saved = validForm.toProfileItem(initial, EConfigType.MASQUE)

        assertEquals(NetworkType.MASQUE.type, saved.network)
        assertEquals(AppConfig.TLS, saved.security)
        assertFalse(saved.insecure == true)
        assertEquals(validForm.masqueHeaders, saved.masqueHeaders)
    }

    @Test
    fun authorizationHeaderCanReplacePassword() {
        assertTrue(ServerValidator.validateForm(EConfigType.MASQUE, validForm).isEmpty())
        assertTrue(
            ServerValidator.validateForm(
                EConfigType.MASQUE,
                validForm.copy(masqueHeaders = "{\"Authorization\":\"Basic dXNlcjpwYXNz\"}")
            ).isEmpty()
        )
        assertTrue(
            ServerField.MASQUE_HEADERS in ServerValidator.validateForm(
                EConfigType.MASQUE,
                validForm.copy(username = "user", password = "pass")
            )
        )
    }

    @Test
    fun invalidHostAndPathAreRejected() {
        val hostErrors = ServerValidator.validateForm(
            EConfigType.MASQUE,
            validForm.copy(host = "https://masque.example.com")
        )
        val pathErrors = ServerValidator.validateForm(
            EConfigType.MASQUE,
            validForm.copy(path = "/masque/{unsupported}/")
        )

        assertTrue(ServerField.HOST in hostErrors)
        assertTrue(ServerField.PATH in pathErrors)
    }

    @Test
    fun warpFormUsesXrayWarpCredentialsInsteadOfBasicAuthentication() {
        val form = validForm.copy(
            username = "ignored",
            password = "ignored",
            masqueHeaders = "X-Client: warp",
            masqueWarpEnabled = true,
            masqueWarpPrivateKey = "private-key",
            masqueWarpPublicKey = "public-key",
            masqueWarpAddress = "172.16.0.2/32,2606:4700:110:8a36::2/128"
        )

        val saved = form.toProfileItem(ProfileItem.create(EConfigType.MASQUE), EConfigType.MASQUE)

        assertTrue(ServerValidator.validateForm(EConfigType.MASQUE, form).isEmpty())
        assertTrue(saved.masqueWarp)
        assertEquals(form.masqueWarpPrivateKey, saved.masqueWarpPrivateKey)
        assertEquals(form.masqueWarpPublicKey, saved.masqueWarpPublicKey)
        assertEquals(form.masqueWarpAddress, saved.masqueWarpAddress)
        assertNull(saved.username)
        assertNull(saved.password)
    }

    @Test
    fun warpAddressValidationMatchesXrayOneAddressPerFamilyRule() {
        val errors = ServerValidator.validateForm(
            EConfigType.MASQUE,
            validForm.copy(
                masqueWarpEnabled = true,
                masqueWarpPrivateKey = "private-key",
                masqueWarpPublicKey = "public-key",
                masqueWarpAddress = "172.16.0.2/32,172.16.0.3/32"
            )
        )

        assertTrue(ServerField.MASQUE_WARP_ADDRESS in errors)
    }
}
