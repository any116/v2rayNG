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
    fun s3FieldsRoundTripThroughVlessUri() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "S3"
            server = "xdrive.example.com"
            serverPort = "443"
            password = "uuid"
            method = "native-encryption"
            network = NetworkType.XDRIVE.type
            remoteFolder = "streams/client/"
            service = AppConfig.XDRIVE_SERVICE_S3
            secrets = "access\nsecret+/=\ntoken+/="
            s3 = """{"endpoint":"https://s3.example.com/base%2Fpath","bucket":"bucket","pathStyle":true}"""
        }

        val parsed = checkNotNull(VlessFmt.parse(AppConfig.VLESS + VlessFmt.toUri(profile)))

        assertEquals(profile.network, parsed.network)
        assertEquals(profile.service, parsed.service)
        assertEquals(profile.remoteFolder, parsed.remoteFolder)
        assertEquals(profile.secrets, parsed.secrets)
        assertEquals(profile.s3, parsed.s3)
    }

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
