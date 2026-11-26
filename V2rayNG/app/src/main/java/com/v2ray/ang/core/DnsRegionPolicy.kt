package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal data class DnsRegionPolicy(val domains: List<String>, val expectedIp: String) {
    companion object {
        private val countryCode = Regex("[a-z]{2}")

        /** Pair enabled direct GeoIP and GeoSite rules without relying on their positions. */
        fun detect(context: CoreConfigContext): List<DnsRegionPolicy> {
            val assetPath = Utils.userAssetPath(context.context).ifEmpty { return emptyList() }
            return detect(context.routingRulesets, context.routingDomainRules, File(assetPath))
        }

        /** Explicit inputs keep asset probing testable without Android storage or preference state. */
        fun detect(
            routingRulesets: List<RulesetItem>,
            routingDomainRules: List<CoreConfigContext.RoutingDomainRule>,
            assetDirectory: File
        ): List<DnsRegionPolicy> {
            val regions = routingRulesets.asSequence()
                .filter { it.enabled && it.outboundTag == AppConfig.TAG_DIRECT }
                .flatMap { it.ip.orEmpty().asSequence() }
                .filter { it.startsWith("geoip:") }
                .map { it.removePrefix("geoip:").lowercase() }
                .filter { countryCode.matches(it) }
                .toSet()
            if (regions.isEmpty()) return emptyList()

            val candidates = routingDomainRules.asSequence()
                .filter { it.outboundTag == AppConfig.TAG_DIRECT }
                .flatMap { it.domain.asSequence() }
                .distinct()
                .filter { it.startsWith("geosite:") }
                .mapNotNull { domain ->
                    val siteAndAttributes = domain.removePrefix("geosite:").lowercase().split('@')
                    val site = siteAndAttributes.first()
                    val region = (sequenceOf(site, site.substringAfterLast('-', "")) +
                        siteAndAttributes.asSequence().drop(1)).firstOrNull { it in regions }
                    region?.let { RegionDomain(domain, site, it) }
                }
                .toList()
            if (candidates.isEmpty()) return emptyList()

            val availableSites = findGeoCodes(
                File(assetDirectory, AppConfig.GEOSITE_DAT), candidates.mapTo(mutableSetOf()) { it.site }
            )
            val domainsByRegion = candidates.filter { it.site in availableSites }
                .groupBy({ it.region }, { it.domain })
            val availableRegions = domainsByRegion.keys.groupBy(::geoIpAsset)
                .flatMapTo(mutableSetOf()) { (asset, codes) -> findGeoCodes(File(assetDirectory, asset), codes.toSet()) }

            return domainsByRegion.mapNotNull { (region, domains) ->
                if (region !in availableRegions) return@mapNotNull null
                // Match the split CN asset used when generating routing rules.
                val expectedIp = if (region == "cn") "ext:${geoIpAsset(region)}:$region" else "geoip:$region"
                DnsRegionPolicy(domains, expectedIp)
            }
        }

        private fun geoIpAsset(region: String): String =
            if (region == "cn") AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT else AppConfig.GEOIP_DAT

        private data class RegionDomain(val domain: String, val site: String, val region: String)

        /**
         * Probe codes without loading domain/CIDR payloads. Protobuf field order is not guaranteed;
         * skip unknown fields and bound every read/seek to its enclosing message.
         */
        private fun findGeoCodes(file: File, wanted: Set<String>): Set<String> = try {
            RandomAccessFile(file, "r").use { input ->
                val found = mutableSetOf<String>()
                val fileEnd = input.length()
                val maxCodeBytes = wanted.maxOfOrNull { it.toByteArray(Charsets.UTF_8).size } ?: 0
                while (found.size < wanted.size && input.filePointer < fileEnd) {
                    val tag = input.readGeoSize(fileEnd)
                    if (tag != 0x0aL) {
                        input.skipGeoField(tag, fileEnd)
                        continue
                    }
                    val entryEnd = input.geoFieldEnd(fileEnd)
                    var code: String? = null
                    while (input.filePointer < entryEnd) {
                        val entryTag = input.readGeoSize(entryEnd)
                        if (entryTag == 0x0aL) {
                            val codeEnd = input.geoFieldEnd(entryEnd)
                            val codeSize = codeEnd - input.filePointer
                            code = if (codeSize in 1..maxCodeBytes.toLong()) {
                                val bytes = ByteArray(codeSize.toInt()).also { input.readFully(it) }
                                String(bytes, Charsets.UTF_8).lowercase()
                            } else {
                                null
                            }
                            // Codes are metadata; once found, skip the potentially huge payload.
                            input.seek(entryEnd)
                            break
                        } else {
                            input.skipGeoField(entryTag, entryEnd)
                        }
                    }
                    if (code != null && code in wanted) found.add(code)
                }
                found
            }
        } catch (_: IOException) {
            emptySet()
        }

        private fun RandomAccessFile.geoFieldEnd(messageEnd: Long): Long {
            val size = readGeoSize(messageEnd)
            if (size > messageEnd - filePointer) throw IOException("Invalid Geo data size")
            return filePointer + size
        }

        private fun RandomAccessFile.skipGeoField(tag: Long, messageEnd: Long) {
            if (tag ushr 3 == 0L) throw IOException("Invalid Geo data field")
            val end = when ((tag and 7).toInt()) {
                0 -> {
                    // Unknown scalar fields may use a full 64-bit varint.
                    for (index in 0..9) {
                        if (filePointer >= messageEnd) throw IOException("Truncated Geo data field")
                        val byte = readUnsignedByte()
                        if (index == 9 && byte > 1) throw IOException("Invalid Geo data varint")
                        if (byte and 0x80 == 0) return
                    }
                    throw IOException("Invalid Geo data varint")
                }
                1 -> filePointer + 8
                2 -> geoFieldEnd(messageEnd)
                5 -> filePointer + 4
                else -> throw IOException("Unsupported Geo data wire type")
            }
            if (end > messageEnd) throw IOException("Truncated Geo data field")
            seek(end)
        }

        private fun RandomAccessFile.readGeoSize(messageEnd: Long): Long {
            var length = 0L
            for (shift in 0..28 step 7) {
                if (filePointer >= messageEnd) throw IOException("Truncated Geo data length")
                val byte = readUnsignedByte()
                if (shift == 28 && byte > 0x0f) throw IOException("Invalid Geo data length")
                length = length or ((byte and 0x7f).toLong() shl shift)
                if ((byte and 0x80) == 0) return length
            }
            throw IOException("Invalid Geo data length")
        }
    }
}
