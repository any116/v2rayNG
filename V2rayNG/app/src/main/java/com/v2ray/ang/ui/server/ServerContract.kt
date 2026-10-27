package com.v2ray.ang.ui.server

import androidx.compose.runtime.Immutable
import com.v2ray.ang.AppConfig.DEFAULT_PORT
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.TLS
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
import com.v2ray.ang.AppConfig.WIREGUARD_LOCAL_MTU
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_GOOGLE_DRIVE
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_LOCAL
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_TEMPLATE
import com.v2ray.ang.dto.SubscriptionOption
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.ui.base.BaseAction
import com.v2ray.ang.ui.base.BaseEvent
import com.v2ray.ang.ui.base.BaseUiState
import java.util.UUID

@Immutable
data class ChainMember(val id: String = UUID.randomUUID().toString(), val remarks: String = "")

@Immutable
data class ServerOptions(
    val subscriptions: List<SubscriptionOption> = emptyList()
)

/** Immutable input boundary; editing lives exclusively in the UI's ServerEditorState. */
@Immutable
data class ServerForm(
    val remarks: String = "",
    val address: String = "",
    val port: String = DEFAULT_PORT.toString(),
    val password: String = "",
    val method: String = "",
    val flow: String = "",
    val encryption: String = "",
    val username: String = "",
    val secretKey: String = "",
    val publicKey: String = "",
    val preSharedKey: String = "",
    val reserved: String = "0,0,0",
    val localAddress: String = WIREGUARD_LOCAL_ADDRESS_V4,
    val mtu: String = WIREGUARD_LOCAL_MTU,
    val remoteDNS: String = "",
    val obfsPassword: String = "",
    val portHopping: String = "",
    val portHoppingInterval: String = "",
    val bandwidthDown: String = "",
    val bandwidthUp: String = "",
    val network: String = NetworkType.TCP.type,
    val headerType: String = "none",
    val host: String = "",
    val path: String = "",
    val xhttpExtra: String = "",
    val masqueHeaders: String = "",
    val masqueWarpEnabled: Boolean = false,
    val masqueWarpPrivateKey: String = "",
    val masqueWarpPublicKey: String = "",
    val masqueWarpAddress: String = "",
    val remoteFolder: String = "",
    val service: String = XDRIVE_SERVICE_LOCAL,
    val secrets: String = "",
    val template: String = "",
    val finalMask: String = "",
    val kcpMtu: String = "",
    val kcpTti: String = "",
    val browserDialerMode: String = "",
    val streamSecurity: String = "",
    val sni: String = "",
    val allowInsecure: Boolean = false,
    val fingerPrint: String = "",
    val alpn: String = "",
    val publicKeyReality: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val mldsa65Verify: String = "",
    val echConfigList: String = "",
    val verifyPeerCertByName: String = "",
    val pinnedCA256: String = "",
    val groupType: String = "0",
    val groupSubId: String = "",
    val groupFilter: String = "",
    val groupTestOutbounds: Boolean = true,
    val groupFallbackTag: String = "",
    val chainMembers: List<ChainMember> = listOf(ChainMember(), ChainMember()),
    val mode: String = "",
    val xhttpMode: String = "",
    val serviceName: String = "",
    val authority: String = "",
    val seed: String = ""
) {

    fun toProfileItem(initial: ProfileItem, configType: EConfigType): ProfileItem {
        val isVmess = configType == EConfigType.VMESS
        val isVless = configType == EConfigType.VLESS
        val isShadowsocks = configType == EConfigType.SHADOWSOCKS
        val isSocksOrHttp = configType == EConfigType.SOCKS || configType == EConfigType.HTTP
        val isWireguard = configType == EConfigType.WIREGUARD
        val isHysteria2 = configType == EConfigType.HYSTERIA2
        val isMasque = configType == EConfigType.MASQUE
        val isXdrive = network == NetworkType.XDRIVE.type

        return initial.copy(
            configType = configType,
            remarks = remarks,
            server = address,
            serverPort = port,
            password = if (isMasque && masqueWarpEnabled) null else password,
            method = when {
                isVmess || isShadowsocks -> method
                isVless -> encryption
                else -> null
            },
            flow = if (isVless && !isXdrive) flow else null,
            username = if (isSocksOrHttp || (isMasque && !masqueWarpEnabled)) username else null,
            secretKey = if (isWireguard) secretKey else null,
            publicKey = when {
                isWireguard -> publicKey
                streamSecurity == REALITY -> publicKeyReality
                else -> null
            },
            preSharedKey = if (isWireguard) preSharedKey else null,
            reserved = if (isWireguard) reserved else null,
            localAddress = if (isWireguard) localAddress else null,
            mtu = if (isWireguard) mtu.toIntOrNull() else null,
            remoteDNS = if (isWireguard || isMasque) remoteDNS.nullIfBlank() else null,
            obfsPassword = if (isHysteria2) obfsPassword else null,
            portHopping = if (isHysteria2) portHopping else null,
            portHoppingInterval = if (isHysteria2) portHoppingInterval else null,
            bandwidthDown = if (isHysteria2) bandwidthDown else null,
            bandwidthUp = if (isHysteria2) bandwidthUp else null,
            network = if (isMasque) NetworkType.MASQUE.type else network,
            headerType = headerType,
            mode = mode.nullIfBlank(),
            xhttpMode = xhttpMode.nullIfBlank(),
            serviceName = serviceName.nullIfBlank(),
            authority = authority.nullIfBlank(),
            host = host,
            path = path,
            xhttpExtra = xhttpExtra.nullIfBlank(),
            masqueHeaders = if (isMasque) masqueHeaders.nullIfBlank() else null,
            masqueWarp = isMasque && masqueWarpEnabled,
            masqueWarpPrivateKey = if (isMasque && masqueWarpEnabled) masqueWarpPrivateKey.nullIfBlank() else null,
            masqueWarpPublicKey = if (isMasque && masqueWarpEnabled) masqueWarpPublicKey.nullIfBlank() else null,
            masqueWarpAddress = if (isMasque && masqueWarpEnabled) masqueWarpAddress.nullIfBlank() else null,
            remoteFolder = if (isXdrive) remoteFolder.nullIfBlank() else null,
            service = if (isXdrive) service.nullIfBlank() else null,
            secrets = if (isXdrive && service in setOf(XDRIVE_SERVICE_GOOGLE_DRIVE, XDRIVE_SERVICE_TEMPLATE)) {
                secrets.nullIfBlank()
            } else {
                null
            },
            template = if (isXdrive && service == XDRIVE_SERVICE_TEMPLATE) template.nullIfBlank() else null,
            finalMask = finalMask.nullIfBlank(),
            seed = seed.nullIfBlank(),
            kcpMtu = kcpMtu.toIntOrNull(),
            kcpTti = kcpTti.toIntOrNull(),
            browserDialerMode =
                if (network in listOf(NetworkType.WS.type, NetworkType.XHTTP.type))
                    browserDialerMode.nullIfBlank() else null,
            security = if (isMasque) TLS else streamSecurity,
            sni = sni,
            insecure = if (isMasque) false else allowInsecure,
            fingerPrint = fingerPrint,
            alpn = alpn,
            shortId = shortId,
            spiderX = spiderX,
            mldsa65Verify = mldsa65Verify,
            echConfigList = echConfigList,
            verifyPeerCertByName = verifyPeerCertByName,
            pinnedCA256 = pinnedCA256
        )
    }

    companion object {
        fun from(profile: ProfileItem): ServerForm {
            val members = profile.proxyChainProfiles
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { ChainMember(remarks = it) }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(ChainMember(), ChainMember())

            val groupType = profile.policyGroupType?.toIntOrNull()?.toString() ?: "0"
            return ServerForm(
                remarks = profile.remarks,
                address = profile.server.orEmpty(),
                port = profile.serverPort ?: DEFAULT_PORT.toString(),
                password = profile.password.orEmpty(),
                method = profile.method.orEmpty(),
                flow = profile.flow.orEmpty(),
                encryption = profile.method.orEmpty(),
                username = profile.username.orEmpty(),
                secretKey = profile.secretKey.orEmpty(),
                publicKey = profile.publicKey.orEmpty(),
                preSharedKey = profile.preSharedKey.orEmpty(),
                reserved = profile.reserved ?: "0,0,0",
                localAddress = profile.localAddress ?: WIREGUARD_LOCAL_ADDRESS_V4,
                mtu = profile.mtu?.toString() ?: WIREGUARD_LOCAL_MTU,
                remoteDNS = profile.remoteDNS.orEmpty(),
                obfsPassword = profile.obfsPassword.orEmpty(),
                portHopping = profile.portHopping.orEmpty(),
                portHoppingInterval = profile.portHoppingInterval.orEmpty(),
                bandwidthDown = profile.bandwidthDown.orEmpty(),
                bandwidthUp = profile.bandwidthUp.orEmpty(),
                network = profile.network ?: NetworkType.TCP.type,
                headerType = profile.headerType ?: "none",
                mode = profile.mode.orEmpty(),
                xhttpMode = profile.xhttpMode.orEmpty(),
                serviceName = profile.serviceName.orEmpty(),
                authority = profile.authority.orEmpty(),
                host = profile.host.orEmpty(),
                path = profile.path.orEmpty(),
                xhttpExtra = profile.xhttpExtra.orEmpty(),
                masqueHeaders = profile.masqueHeaders.orEmpty(),
                masqueWarpEnabled = profile.masqueWarp,
                masqueWarpPrivateKey = profile.masqueWarpPrivateKey.orEmpty(),
                masqueWarpPublicKey = profile.masqueWarpPublicKey.orEmpty(),
                masqueWarpAddress = profile.masqueWarpAddress.orEmpty(),
                remoteFolder = profile.remoteFolder.orEmpty(),
                service = profile.service ?: XDRIVE_SERVICE_LOCAL,
                secrets = profile.secrets.orEmpty(),
                template = profile.template.orEmpty(),
                finalMask = profile.finalMask.orEmpty(),
                seed = profile.seed.orEmpty(),
                kcpMtu = profile.kcpMtu?.toString().orEmpty(),
                kcpTti = profile.kcpTti?.toString().orEmpty(),
                browserDialerMode = profile.browserDialerMode.orEmpty(),
                streamSecurity = if (profile.configType == EConfigType.MASQUE) TLS else profile.security.orEmpty(),
                sni = profile.sni.orEmpty(),
                allowInsecure = profile.insecure == true,
                fingerPrint = profile.fingerPrint.orEmpty(),
                alpn = profile.alpn.orEmpty(),
                publicKeyReality = profile.publicKey.orEmpty(),
                shortId = profile.shortId.orEmpty(),
                spiderX = profile.spiderX.orEmpty(),
                mldsa65Verify = profile.mldsa65Verify.orEmpty(),
                echConfigList = profile.echConfigList.orEmpty(),
                verifyPeerCertByName = profile.verifyPeerCertByName.orEmpty(),
                pinnedCA256 = profile.pinnedCA256.orEmpty(),
                groupType = groupType,
                groupSubId = profile.policyGroupSubscriptionId.orEmpty(),
                groupFilter = profile.policyGroupFilter.orEmpty(),
                groupTestOutbounds = profile.policyGroupTestOutbounds != false ||
                        !BalancerStrategyType.from(groupType).supportsObservatory,
                groupFallbackTag = profile.policyGroupFallbackTag.orEmpty(),
                chainMembers = members
            )
        }
    }
}

enum class ServerField(
    val get: (ServerForm) -> String,
    val set: (ServerForm, String) -> ServerForm
) {
    REMARKS({ it.remarks }, { f, v -> f.copy(remarks = v) }),
    ADDRESS({ it.address }, { f, v -> f.copy(address = v) }),
    PORT({ it.port }, { f, v -> f.copy(port = v) }),
    PASSWORD({ it.password }, { f, v -> f.copy(password = v) }),
    METHOD({ it.method }, { f, v -> f.copy(method = v) }),
    FLOW({ it.flow }, { f, v -> f.copy(flow = v) }),
    ENCRYPTION({ it.encryption }, { f, v -> f.copy(encryption = v) }),
    USERNAME({ it.username }, { f, v -> f.copy(username = v) }),
    SECRET_KEY({ it.secretKey }, { f, v -> f.copy(secretKey = v) }),
    PUBLIC_KEY({ it.publicKey }, { f, v -> f.copy(publicKey = v) }),
    PRE_SHARED_KEY({ it.preSharedKey }, { f, v -> f.copy(preSharedKey = v) }),
    RESERVED({ it.reserved }, { f, v -> f.copy(reserved = v) }),
    LOCAL_ADDRESS({ it.localAddress }, { f, v -> f.copy(localAddress = v) }),
    MTU({ it.mtu }, { f, v -> f.copy(mtu = v) }),
    REMOTE_DNS({ it.remoteDNS }, { f, v -> f.copy(remoteDNS = v) }),
    OBFS_PASSWORD({ it.obfsPassword }, { f, v -> f.copy(obfsPassword = v) }),
    PORT_HOPPING({ it.portHopping }, { f, v -> f.copy(portHopping = v) }),
    PORT_HOPPING_INTERVAL({ it.portHoppingInterval }, { f, v -> f.copy(portHoppingInterval = v) }),
    BANDWIDTH_DOWN({ it.bandwidthDown }, { f, v -> f.copy(bandwidthDown = v) }),
    BANDWIDTH_UP({ it.bandwidthUp }, { f, v -> f.copy(bandwidthUp = v) }),
    NETWORK({ it.network }, { f, v -> f.copy(network = v) }),
    HEADER_TYPE({ it.headerType }, { f, v -> f.copy(headerType = v) }),
    HOST({ it.host }, { f, v -> f.copy(host = v) }),
    PATH({ it.path }, { f, v -> f.copy(path = v) }),
    XHTTP_EXTRA({ it.xhttpExtra }, { f, v -> f.copy(xhttpExtra = v) }),
    MASQUE_HEADERS({ it.masqueHeaders }, { f, v -> f.copy(masqueHeaders = v) }),
    MASQUE_WARP_PRIVATE_KEY({ it.masqueWarpPrivateKey }, { f, v -> f.copy(masqueWarpPrivateKey = v) }),
    MASQUE_WARP_PUBLIC_KEY({ it.masqueWarpPublicKey }, { f, v -> f.copy(masqueWarpPublicKey = v) }),
    MASQUE_WARP_ADDRESS({ it.masqueWarpAddress }, { f, v -> f.copy(masqueWarpAddress = v) }),
    REMOTE_FOLDER({ it.remoteFolder }, { f, v -> f.copy(remoteFolder = v) }),
    SERVICE({ it.service }, { f, v -> f.copy(service = v) }),
    SECRETS({ it.secrets }, { f, v -> f.copy(secrets = v) }),
    TEMPLATE({ it.template }, { f, v -> f.copy(template = v) }),
    FINAL_MASK({ it.finalMask }, { f, v -> f.copy(finalMask = v) }),
    KCP_MTU({ it.kcpMtu }, { f, v -> f.copy(kcpMtu = v) }),
    KCP_TTI({ it.kcpTti }, { f, v -> f.copy(kcpTti = v) }),
    BROWSER_DIALER({ it.browserDialerMode }, { f, v -> f.copy(browserDialerMode = v) }),
    STREAM_SECURITY({ it.streamSecurity }, { f, v -> f.copy(streamSecurity = v) }),
    SNI({ it.sni }, { f, v -> f.copy(sni = v) }),
    FINGERPRINT({ it.fingerPrint }, { f, v -> f.copy(fingerPrint = v) }),
    ALPN({ it.alpn }, { f, v -> f.copy(alpn = v) }),
    PUBLIC_KEY_REALITY({ it.publicKeyReality }, { f, v -> f.copy(publicKeyReality = v) }),
    SHORT_ID({ it.shortId }, { f, v -> f.copy(shortId = v) }),
    SPIDER_X({ it.spiderX }, { f, v -> f.copy(spiderX = v) }),
    MLDSA65_VERIFY({ it.mldsa65Verify }, { f, v -> f.copy(mldsa65Verify = v) }),
    ECH_CONFIG_LIST({ it.echConfigList }, { f, v -> f.copy(echConfigList = v) }),
    VERIFY_PEER_CERT({ it.verifyPeerCertByName }, { f, v -> f.copy(verifyPeerCertByName = v) }),
    PINNED_CA256({ it.pinnedCA256 }, { f, v -> f.copy(pinnedCA256 = v) }),
    GROUP_TYPE({ it.groupType }, { f, v -> f.copy(groupType = v) }),
    GROUP_SUB_ID({ it.groupSubId }, { f, v -> f.copy(groupSubId = v) }),
    GROUP_FILTER({ it.groupFilter }, { f, v -> f.copy(groupFilter = v) }),
    GROUP_FALLBACK_TAG({ it.groupFallbackTag }, { f, v -> f.copy(groupFallbackTag = v) }),
    MODE({ it.mode }, { f, v -> f.copy(mode = v) }),
    XHTTP_MODE({ it.xhttpMode }, { f, v -> f.copy(xhttpMode = v) }),
    SERVICE_NAME({ it.serviceName }, { f, v -> f.copy(serviceName = v) }),
    AUTHORITY({ it.authority }, { f, v -> f.copy(authority = v) }),
    SEED({ it.seed }, { f, v -> f.copy(seed = v) });
}

@Immutable
data class ServerEditorSnapshot(val form: ServerForm, val rawContent: String)

/** A validation result belongs to the submitted value, not to subsequent edits in the buffer. */
@Immutable
data class ServerFieldError(val message: Int, val submittedValue: String)

internal fun ServerForm.validationErrors(errors: Map<ServerField, Int>): Map<ServerField, ServerFieldError> =
    errors.mapValues { (field, message) -> ServerFieldError(message, field.get(this)) }

sealed interface ServerEvent : BaseEvent.Platform {
    data object ConfirmDeleteProfile : ServerEvent
    data class CertificateFetched(val pinnedCA256: String) : ServerEvent
}

@Immutable
data class ServerHeader(
    val configType: EConfigType = EConfigType.VMESS,
    val canDelete: Boolean = false,
    val isLoaded: Boolean = false
)

@Immutable
data class ServerUiState(
    val configType: EConfigType = EConfigType.VMESS,
    val guid: String = "",
    val isRunning: Boolean = false,
    val initialForm: ServerForm? = null,
    val isLoaded: Boolean = false,
    val options: ServerOptions = ServerOptions(),
    val initialRawContent: String = "",
    val isFetchingCert: Boolean = false,
    val fieldErrors: Map<ServerField, ServerFieldError> = emptyMap()
) : BaseUiState {

    val isEdit: Boolean get() = guid.isNotEmpty()

    val canDelete: Boolean get() = isLoaded && isEdit && !isRunning

    val header: ServerHeader
        get() = ServerHeader(configType, canDelete, isLoaded)
}

sealed interface ServerAction : BaseAction {
    data class Save(val form: ServerForm, val rawContent: String) : ServerAction
    data object Back : ServerAction
    data object DeleteClicked : ServerAction
    data object ConfirmDeleteProfile : ServerAction
    data class FetchCertificate(val form: ServerForm) : ServerAction
}
