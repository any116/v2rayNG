package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.removeWhiteSpace
import com.v2ray.ang.util.Utils
import java.net.URI

object WireguardFmt : FmtBase() {
    /**
     * Parses a URI string into a ProfileItem object.
     *
     * @param str the URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.WIREGUARD)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "none" }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()

        config.secretKey = uri.userInfo.orEmpty()
        config.localAddress = queryParam["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.publicKey = queryParam["publickey"].orEmpty()
        config.preSharedKey = queryParam["presharedkey"]?.nullIfBlank()
        config.mtu = Utils.parseInt(queryParam["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.reserved = queryParam["reserved"] ?: "0,0,0"
        config.finalMask = (queryParam["fm"] ?: queryParam["finalmask"] ?: queryParam["finalMask"])?.nullIfBlank()
        config.remoteDNS = queryParam.firstValue("dns", "remoteDNS", "remotedns", "remoteDns")?.nullIfBlank()

        return config
    }

    /**
     * Parses a Wireguard configuration file string into a ProfileItem object.
     *
     * @param str the Wireguard configuration file string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parseWireguardConfFile(str: String): ProfileItem {
        val config = ProfileItem.create(EConfigType.WIREGUARD)

        val interfaceParams = mutableMapOf<String, String>()
        val peerParams = mutableMapOf<String, String>()
        var currentParams: MutableMap<String, String>? = null

        for (line in str.lineSequence()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("#") || trimmedLine.startsWith(";")) {
                continue
            }

            when {
                trimmedLine.startsWith("[Interface]", ignoreCase = true) -> currentParams = interfaceParams
                trimmedLine.startsWith("[Peer]", ignoreCase = true) -> currentParams = peerParams
                else -> {
                    val params = currentParams ?: continue
                    val parts = trimmedLine.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim().lowercase()
                        val value = parts[1].substringBefore('#').substringBefore(';').trim()
                        params[key] = value
                    }
                }
            }
        }

        config.secretKey = interfaceParams["privatekey"].orEmpty()
        config.remarks = System.currentTimeMillis().toString()
        config.localAddress = interfaceParams["address"] ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        config.mtu = Utils.parseInt(interfaceParams["mtu"] ?: AppConfig.WIREGUARD_LOCAL_MTU)
        config.publicKey = peerParams["publickey"].orEmpty()
        config.preSharedKey = peerParams["presharedkey"]?.nullIfBlank()
        val endpoint = peerParams["endpoint"].orEmpty()
        val endpointParts = splitEndpoint(endpoint)
        config.server = endpointParts.first
        config.serverPort = endpointParts.second
        config.reserved = peerParams["reserved"] ?: "0,0,0"
        config.finalMask = (peerParams["finalmask"] ?: peerParams["fm"] ?: interfaceParams["finalmask"] ?: interfaceParams["fm"])?.nullIfBlank()
        config.remoteDNS = (interfaceParams["remotedns"] ?: interfaceParams["dns"])?.nullIfBlank()

        return config
    }


    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val dicQuery = HashMap<String, String>()

        dicQuery["publickey"] = config.publicKey.orEmpty()
        config.reserved?.let { dicQuery["reserved"] = it.removeWhiteSpace().orEmpty() }
        dicQuery["address"] = config.localAddress.removeWhiteSpace().orEmpty()
        config.mtu?.let { dicQuery["mtu"] = it.toString() }
        config.preSharedKey?.let { dicQuery["presharedkey"] = it.removeWhiteSpace().orEmpty() }
        config.finalMask?.nullIfBlank()?.let { dicQuery["fm"] = it }
        config.remoteDNS?.nullIfBlank()?.let { dicQuery["dns"] = it }

        return toUri(config, config.secretKey, dicQuery)
    }

    private fun Map<String, String>.firstValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { this[it] }

    private fun splitEndpoint(endpoint: String): Pair<String, String> {
        val value = endpoint.trim()
        if (value.startsWith("[")) {
            val closingBracket = value.indexOf(']')
            if (closingBracket > 1) {
                return value.substring(1, closingBracket) to value.substring(closingBracket + 1).removePrefix(":")
            }
        }
        val separator = value.lastIndexOf(':')
        return if (separator > 0 && value.indexOf(':') == separator) {
            value.substring(0, separator) to value.substring(separator + 1)
        } else {
            value to ""
        }
    }
}
