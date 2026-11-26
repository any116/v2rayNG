package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.dto.CoreConfigContext.RoutingDomainRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DnsRegionPolicyTest {
    @TempDir
    lateinit var assets: File

    private val rules = listOf(RulesetItem(ip = listOf("geoip:cn"), outboundTag = AppConfig.TAG_DIRECT))
    private val domains = listOf(RoutingDomainRule(listOf("geosite:cn"), AppConfig.TAG_DIRECT))
    private val expected = listOf(DnsRegionPolicy(listOf("geosite:cn"), "ext:geoip-only-cn-private.dat:cn"))

    @Test
    fun detectsCountryWithCodeAfterPayloadAndUnknownFields() {
        // Protobuf fields may appear in any order. Field 2 is the domain/CIDR payload.
        val entry = field(2, byteArrayOf(8, 1)) + byteArrayOf(24, 1) + field(1, "CN".toByteArray())
        install(field(1, entry))

        assertEquals(expected, detect())
    }

    @Test
    fun skipsUnknownTopLevelFieldsAndReadsMultiByteLengths() {
        val unknownFields = byteArrayOf(16, 1, 29, 0, 0, 0, 0, 33, 0, 0, 0, 0, 0, 0, 0, 0)
        val entry = field(2, ByteArray(256)) + field(1, "CN".toByteArray())
        install(unknownFields + field(1, entry))

        assertEquals(expected, detect())
    }

    @Test
    fun standardCodeFirstFilesStillWork() {
        install(field(1, field(1, "CN".toByteArray()) + field(2, ByteArray(256))))
        assertEquals(expected, detect())
    }

    @Test
    fun missingAndTruncatedAssetsDisableValidationWithoutThrowing() {
        assertTrue(detect().isEmpty())
        listOf(
            byteArrayOf(10, 10, 10, 2, 67, 78),
            byteArrayOf(10, 2, 10, 3),
            byteArrayOf(10, 1, 0),
            byteArrayOf(10, -128, -128, -128, -128, -128, 1)
        ).forEach { malformed ->
            install(malformed)
            assertTrue(detect().isEmpty())
        }
    }

    @Test
    fun readsMatchingCodeFromLaterEntry() {
        install(field(1, field(1, "US".toByteArray())) + field(1, field(1, "CN".toByteArray())))
        assertEquals(expected, detect())
    }

    @Test
    fun disabledOrNonDirectGeoipDoesNotEnableValidation() {
        install(field(1, field(1, "CN".toByteArray())))
        assertTrue(DnsRegionPolicy.detect(rules.map { it.copy(enabled = false) }, domains, assets).isEmpty())
        assertTrue(DnsRegionPolicy.detect(
            rules.map { it.copy(outboundTag = AppConfig.TAG_PROXY) }, domains, assets
        ).isEmpty())
    }

    @Test
    fun cnRequiresSplitGeoipAssetRatherThanGenericGeoip() {
        install(field(1, field(1, "CN".toByteArray())))
        File(assets, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT).renameTo(File(assets, AppConfig.GEOIP_DAT))
        assertTrue(detect().isEmpty())
    }

    private fun detect() = DnsRegionPolicy.detect(rules, domains, assets)

    private fun install(bytes: ByteArray) {
        File(assets, AppConfig.GEOSITE_DAT).writeBytes(bytes)
        File(assets, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT).writeBytes(bytes)
    }

    private fun field(number: Int, bytes: ByteArray): ByteArray =
        varint((number shl 3) or 2) + varint(bytes.size) + bytes

    private fun varint(value: Int): ByteArray {
        var remaining = value
        val result = ArrayList<Byte>()
        do {
            val bits = remaining and 0x7f
            remaining = remaining ushr 7
            result.add((bits or if (remaining != 0) 0x80 else 0).toByte())
        } while (remaining != 0)
        return result.toByteArray()
    }
}
