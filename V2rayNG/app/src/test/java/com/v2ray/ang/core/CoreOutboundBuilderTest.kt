package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun invalidXdriveProfilesAreNotConvertedIntoCoreOutbounds() {
        assertNull(CoreOutboundBuilder.convert(xdriveProfile()))
        assertNull(CoreOutboundBuilder.convert(xdriveProfile().apply { flow = "xtls-rprx-vision" }))
        assertNull(CoreOutboundBuilder.convert(xdriveProfile().apply { method = "none" }))
        assertNull(CoreOutboundBuilder.convert(xdriveProfile().copy(configType = EConfigType.VMESS)))
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
