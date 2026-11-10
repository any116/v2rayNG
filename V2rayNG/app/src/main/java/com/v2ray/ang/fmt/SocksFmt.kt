package com.v2ray.ang.fmt

import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import okio.ByteString.Companion.encodeUtf8
import java.net.URI

object SocksFmt : FmtBase() {
    /**
     * Parses a Socks URI string into a ProfileItem object.
     *
     * @param str the Socks URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.SOCKS)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.idnHost.isEmpty()) return null
        if (uri.port <= 0) return null

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "none" }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()

        val userInfo = uri.userInfo
        if (!userInfo.isNullOrEmpty()) {
            val credentials = if (userInfo.contains(":")) userInfo else Utils.decode(userInfo)
            val parts = credentials.split(":", limit = 2)
            if (parts.size == 2) {
                config.username = parts.first()
                config.password = parts.last()
            }
        }

        return config
    }

    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val userInfo = if (config.username.isNullOrBlank()) ":" else "${config.username}:${config.password}"
        return toUri(config, userInfo.encodeUtf8().base64(includePadding = false), null)
    }
}
