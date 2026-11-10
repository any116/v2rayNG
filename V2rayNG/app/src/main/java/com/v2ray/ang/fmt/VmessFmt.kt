package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.dto.VmessQRCode
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import okio.ByteString.Companion.encodeUtf8
import java.net.URI

object VmessFmt : FmtBase() {
    /**
     * Parses a Vmess string into a ProfileItem object.
     *
     * @param str the Vmess string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parse(str: String): ProfileItem? {
        if (str.indexOf('?') > 0 && str.indexOf('&') > 0) {
            return parseVmessStd(str)
        }

        val config = ProfileItem.create(EConfigType.VMESS)

        val result = Utils.decode(str.replace(EConfigType.VMESS.protocolScheme, ""))
        if (result.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "Toast decoding failed")
            return null
        }
        val vmessQRCode = JsonUtil.fromJson(result, VmessQRCode::class.java) ?: return null
        // Although VmessQRCode fields are non null, looks like Gson may still create null fields
        if (vmessQRCode.add.isNullOrEmpty()
            || vmessQRCode.port.isNullOrEmpty()
            || vmessQRCode.id.isNullOrEmpty()
            || vmessQRCode.net.isNullOrEmpty()
        ) {
            LogUtil.w(AppConfig.TAG, "Toast incorrect protocol")
            return null
        }

        config.remarks = vmessQRCode.ps
        config.server = vmessQRCode.add
        config.serverPort = vmessQRCode.port
        config.password = vmessQRCode.id
        config.method = vmessQRCode.scy.takeUnless { it.isNullOrEmpty() } ?: AppConfig.DEFAULT_SECURITY

        config.network = vmessQRCode.net
        config.headerType = vmessQRCode.type
        config.host = vmessQRCode.host
        config.path = vmessQRCode.path

        when (NetworkType.fromString(config.network)) {
            NetworkType.KCP -> {
                config.seed = vmessQRCode.path
            }

            NetworkType.GRPC -> {
                config.mode = vmessQRCode.type
                config.serviceName = vmessQRCode.path
                config.authority = vmessQRCode.host
            }

            else -> {}
        }

        config.security = vmessQRCode.tls
        config.sni = vmessQRCode.sni
        config.fingerPrint = vmessQRCode.fp
        config.alpn = vmessQRCode.alpn
        config.insecure = vmessQRCode.insecure == "1"
        config.verifyPeerCertByName = vmessQRCode.vcn
        config.pinnedCA256 = vmessQRCode.pcs

        return config
    }

    /**
     * Converts a ProfileItem object to a URI string.
     *
     * @param config the ProfileItem object to convert
     * @return the converted URI string
     */
    fun toUri(config: ProfileItem): String {
        val vmessQRCode = VmessQRCode()

        vmessQRCode.v = "2"
        vmessQRCode.ps = config.remarks
        vmessQRCode.add = config.server.orEmpty()
        vmessQRCode.port = config.serverPort.orEmpty()
        vmessQRCode.id = config.password.orEmpty()
        vmessQRCode.scy = config.method.orEmpty()
        vmessQRCode.aid = "0"

        vmessQRCode.net = config.network.orEmpty()
        vmessQRCode.type = config.headerType.orEmpty()
        when (NetworkType.fromString(config.network)) {
            NetworkType.KCP -> {
                vmessQRCode.path = config.seed.orEmpty()
            }

            NetworkType.GRPC -> {
                vmessQRCode.type = config.mode.orEmpty()
                vmessQRCode.path = config.serviceName.orEmpty()
                vmessQRCode.host = config.authority.orEmpty()
            }

            else -> {}
        }

        config.host?.nullIfBlank()?.let { vmessQRCode.host = it }
        config.path?.nullIfBlank()?.let { vmessQRCode.path = it }

        vmessQRCode.tls = config.security.orEmpty()
        vmessQRCode.sni = config.sni.orEmpty()
        vmessQRCode.fp = config.fingerPrint.orEmpty()
        vmessQRCode.alpn = config.alpn.orEmpty()
        vmessQRCode.insecure = when (config.insecure) {
            true -> "1"
            false -> "0"
            else -> ""
        }
        vmessQRCode.vcn = config.verifyPeerCertByName.orEmpty()
        vmessQRCode.pcs = config.pinnedCA256.orEmpty()

        val json = JsonUtil.toJson(vmessQRCode)
        return json.encodeUtf8().base64()
    }

    /**
     * Parses a standard Vmess URI string into a ProfileItem object.
     *
     * @param str the standard Vmess URI string to parse
     * @return the parsed ProfileItem object, or null if parsing fails
     */
    fun parseVmessStd(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.VMESS)

        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.rawQuery.isNullOrEmpty()) return null
        val queryParam = getQueryParam(uri)

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty()).ifEmpty { "none" }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()
        config.password = uri.userInfo
        config.method = AppConfig.DEFAULT_SECURITY

        getItemFormQuery(config, queryParam)

        return config
    }


}
