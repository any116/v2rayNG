package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class VlessFmtTest {

    @Test
    fun xdriveFieldsRoundTripThroughVlessUri() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "XDRIVE"
            server = "xdrive.example.com"
            serverPort = "443"
            password = "uuid"
            method = "native-encryption"
            network = NetworkType.XDRIVE.type
            security = AppConfig.TLS
            remoteFolder = "folder"
            service = AppConfig.XDRIVE_SERVICE_TEMPLATE
            secrets = "user\npass"
            template = "{\"put\":{\"url\":\"https://storage.example/{name}\"}}"
        }

        val parsed = VlessFmt.parse(AppConfig.VLESS + VlessFmt.toUri(profile))

        assertNotNull(parsed)
        assertEquals(NetworkType.XDRIVE.type, parsed?.network)
        assertEquals("folder", parsed?.remoteFolder)
        assertEquals(AppConfig.XDRIVE_SERVICE_TEMPLATE, parsed?.service)
        assertEquals("user\npass", parsed?.secrets)
        assertEquals(profile.template, parsed?.template)
    }
}
