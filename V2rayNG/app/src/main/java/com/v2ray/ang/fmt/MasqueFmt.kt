package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.Utils
import java.net.URI

/** Internal share-link codec for MASQUE profiles. */
object MasqueFmt : FmtBase() {
    fun parse(str: String): ProfileItem? = runCatching {
        val uri = URI(Utils.fixIllegalUrl(str))
        val config = ProfileItem.create(EConfigType.MASQUE)
        val query = if (uri.rawQuery.isNullOrBlank()) emptyMap() else getQueryParam(uri)
        val userInfo = uri.userInfo.orEmpty()
        val separator = userInfo.indexOf(':')
        val warp = query["warp"] == "1" || query["warp"]?.equals("true", ignoreCase = true) == true

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "none" }
        config.server = uri.idnHost
        config.serverPort = (uri.port.takeIf { it > 0 } ?: AppConfig.DEFAULT_PORT).toString()
        config.username = if (warp) null else if (separator >= 0) userInfo.substring(0, separator) else userInfo
        config.password = if (warp) null else if (separator >= 0) userInfo.substring(separator + 1) else ""
        getItemFormQuery(config, query)
        config.network = NetworkType.MASQUE.type
        config.host = query["host"]
        config.path = query["path"]
        config.masqueHeaders = query["headers"]
        config.masqueWarp = warp
        config.masqueWarpPrivateKey = query["warpPrivateKey"]
        config.masqueWarpPublicKey = query["warpPublicKey"]
        config.masqueWarpAddress = query["warpAddress"]
        config.security = AppConfig.TLS
        config.insecure = false
        config.remoteDNS = query["remoteDNS"]
        config
    }.getOrNull()

    fun toUri(config: ProfileItem): String {
        val query = getQueryDic(config).apply {
            put("type", NetworkType.MASQUE.type)
            put("security", AppConfig.TLS)
            remove("insecure")
            remove("allowInsecure")
            remove("warp")
            remove("warpPrivateKey")
            remove("warpPublicKey")
            remove("warpAddress")
            config.host?.nullIfBlank()?.let { put("host", it) }
            config.path?.nullIfBlank()?.let { put("path", it) }
            config.masqueHeaders?.nullIfBlank()?.let { put("headers", it) }
            config.remoteDNS?.nullIfBlank()?.let { put("remoteDNS", it) }
            if (config.masqueWarp) {
                put("warp", "1")
                config.masqueWarpPrivateKey?.nullIfBlank()?.let { put("warpPrivateKey", it) }
                config.masqueWarpPublicKey?.nullIfBlank()?.let { put("warpPublicKey", it) }
                config.masqueWarpAddress?.nullIfBlank()?.let { put("warpAddress", it) }
            }
        }
        val userInfo = when {
            config.masqueWarp -> null
            config.username.isNullOrBlank() && config.password.isNullOrBlank() -> null
            else -> "${config.username.orEmpty()}:${config.password.orEmpty()}"
        }
        return toUri(config, userInfo, query)
    }

}
