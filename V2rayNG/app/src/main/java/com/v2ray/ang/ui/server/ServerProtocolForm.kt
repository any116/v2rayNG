package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.TLS
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

private data class ProtocolSpec(
    val showNetwork: Boolean = false,
    val showStreamSecurity: Boolean = false,
)

private fun EConfigType.spec(): ProtocolSpec = when (this) {
    EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN, EConfigType.SHADOWSOCKS ->
        ProtocolSpec(showNetwork = true, showStreamSecurity = true)
    else -> ProtocolSpec()
}

@Composable
internal fun ProtocolForm(
    configType: EConfigType,
    form: ServerForm,
    fieldErrors: Map<ServerField, Int>,
    isFetchingCert: Boolean,
    onAction: (ServerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = rememberFieldOptions()
    val spec = remember(configType) { configType.spec() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing),
    ) {
        ServerTextField(
            R.string.server_lab_remarks, ServerField.REMARKS, form.remarks, onAction, fieldErrors
        )
        ServerTextField(
            R.string.server_lab_address, ServerField.ADDRESS, form.address, onAction, fieldErrors
        )
        ServerTextField(
            R.string.server_lab_port, ServerField.PORT, form.port, onAction, fieldErrors,
            KeyboardType.Number
        )

        ProtocolFields(configType, form, fieldErrors, isFetchingCert, options, onAction)

        if (spec.showNetwork) NetworkFields(form, options, onAction)
        if (spec.showStreamSecurity) {
            StreamSecurityFields(form, fieldErrors, isFetchingCert, options, onAction)
        }
    }
}

@Composable
private fun ProtocolFields(
    configType: EConfigType,
    form: ServerForm,
    fieldErrors: Map<ServerField, Int>,
    isFetchingCert: Boolean,
    options: FieldOptions,
    onAction: (ServerAction) -> Unit,
) {
    when (configType) {
        EConfigType.VMESS -> {
            ServerTextField(
                R.string.server_lab_id, ServerField.PASSWORD, form.password, onAction, fieldErrors
            )
            ServerDropdownField(
                R.string.server_lab_security, ServerField.METHOD, form.method,
                options.vmessSecurities, onAction, fieldErrors,
            )
        }
        EConfigType.VLESS -> {
            ServerTextField(
                R.string.server_lab_id, ServerField.PASSWORD, form.password, onAction, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_encryption, ServerField.ENCRYPTION, form.encryption,
                onAction, fieldErrors,
            )
            ServerDropdownField(
                R.string.server_lab_flow, ServerField.FLOW, form.flow, options.flows,
                onAction, fieldErrors,
            )
        }
        EConfigType.TROJAN ->
            ServerTextField(
                R.string.server_lab_id3, ServerField.PASSWORD, form.password, onAction, fieldErrors
            )
        EConfigType.SHADOWSOCKS -> {
            ServerTextField(
                R.string.server_lab_id3, ServerField.PASSWORD, form.password, onAction, fieldErrors
            )
            ServerDropdownField(
                R.string.server_lab_security, ServerField.METHOD, form.method,
                options.ssSecurities, onAction, fieldErrors,
            )
        }
        EConfigType.WIREGUARD -> {
            ServerTextField(
                R.string.server_lab_secret_key, ServerField.SECRET_KEY, form.secretKey,
                onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_public_key, ServerField.PUBLIC_KEY, form.publicKey,
                onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_preshared_key, ServerField.PRE_SHARED_KEY, form.preSharedKey,
                onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_reserved, ServerField.RESERVED, form.reserved,
                onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_local_address, ServerField.LOCAL_ADDRESS, form.localAddress,
                onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_local_mtu, ServerField.MTU, form.mtu, onAction, fieldErrors,
                KeyboardType.Number,
            )
            ServerTextField(
                R.string.server_lab_final_mask, ServerField.FINAL_MASK, form.finalMask,
                onAction, fieldErrors,
            )
        }
        EConfigType.HYSTERIA2 -> {
            ServerTextField(
                R.string.server_lab_id3, ServerField.PASSWORD, form.password, onAction, fieldErrors
            )
            ServerTextField(
                R.string.server_obfs_password, ServerField.OBFS_PASSWORD, form.obfsPassword,
                onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_port_hop, ServerField.PORT_HOPPING, form.portHopping,
                onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_port_hop_interval, ServerField.PORT_HOPPING_INTERVAL,
                form.portHoppingInterval, onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_bandwidth_down, ServerField.BANDWIDTH_DOWN,
                form.bandwidthDown, onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_bandwidth_up, ServerField.BANDWIDTH_UP,
                form.bandwidthUp, onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_final_mask, ServerField.FINAL_MASK, form.finalMask,
                onAction, fieldErrors,
            )
            SettingsSwitchItem(
                title = stringResource(R.string.server_lab_allow_insecure),
                checked = form.allowInsecure,
                onCheckedChange = { onAction(ServerAction.FlagChanged(ServerFlag.ALLOW_INSECURE, it)) },
            )
            ServerTextField(
                R.string.server_lab_sni, ServerField.SNI, form.sni, onAction, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_ech_config_list, ServerField.ECH_CONFIG_LIST,
                form.echConfigList, onAction, fieldErrors,
            )
            PinnedCertFields(form.pinnedCA256, isFetchingCert, onAction)
        }
        else -> {
            ServerTextField(
                R.string.server_lab_security4, ServerField.USERNAME, form.username,
                onAction, fieldErrors,
            )
            ServerTextField(
                R.string.server_lab_id4, ServerField.PASSWORD, form.password, onAction, fieldErrors
            )
        }
    }
}

@Composable
private fun PinnedCertFields(
    pinnedCa256: String,
    isFetchingCert: Boolean,
    onAction: (ServerAction) -> Unit,
) {
    FormTextField(
        label = stringResource(R.string.server_lab_pinned_ca256),
        value = pinnedCa256,
        onValueChange = { onAction(ServerAction.TextChanged(ServerField.PINNED_CA256, it)) },
    )
    Button(
        onClick = { onAction(ServerAction.FetchCertificate) },
        enabled = !isFetchingCert,
        modifier = Modifier.padding(horizontal = ServerDimens.ContentHorizontal),
    ) {
        if (isFetchingCert) {
            CircularProgressIndicator(
                modifier = Modifier.size(ServerDimens.InlineProgress),
                strokeWidth = ServerDimens.InlineProgressStroke,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(ServerDimens.InlineSpacing))
        }
        Text(stringResource(R.string.pinned_ca256_action_fetch))
    }
}

@Composable
private fun NetworkFields(
    form: ServerForm,
    options: FieldOptions,
    onAction: (ServerAction) -> Unit,
) {
    val noErrors = emptyMap<ServerField, Int>()
    Column(verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing)) {
        ServerDropdownField(
            R.string.server_lab_network, ServerField.NETWORK, form.network, options.networks,
            onAction, noErrors,
        )

        val headerOptions = when (form.network) {
            NetworkType.TCP.type -> options.tcpHeaders
            NetworkType.KCP.type -> options.kcpHeaders
            NetworkType.GRPC.type -> options.grpcModes
            NetworkType.XHTTP.type -> options.xhttpModes
            else -> null
        }
        if (headerOptions != null && headerOptions.size > 1) {
            val headerField = when (form.network) {
                NetworkType.GRPC.type -> ServerField.MODE
                NetworkType.XHTTP.type -> ServerField.XHTTP_MODE
                else -> ServerField.HEADER_TYPE
            }
            ServerDropdownField(
                labelRes = when (form.network) {
                    NetworkType.GRPC.type -> R.string.server_lab_mode_type
                    NetworkType.XHTTP.type -> R.string.server_lab_xhttp_mode
                    else -> R.string.server_lab_head_type
                },
                field = headerField,
                value = headerField.get(form),
                options = headerOptions,
                onAction = onAction,
                fieldErrors = noErrors,
            )
        }

        val hostField =
            if (form.network == NetworkType.GRPC.type) ServerField.AUTHORITY else ServerField.HOST
        ServerTextField(
            labelRes = when (form.network) {
                NetworkType.TCP.type,
                NetworkType.HTTP_UPGRADE.type,
                NetworkType.XHTTP.type,
                NetworkType.H2.type -> R.string.server_lab_request_host_http
                NetworkType.WS.type -> R.string.server_lab_request_host_ws
                NetworkType.GRPC.type -> R.string.server_lab_request_host_grpc
                else -> R.string.server_lab_request_host6
            },
            field = hostField,
            value = hostField.get(form),
            onAction = onAction,
            fieldErrors = noErrors,
        )

        if (form.network != NetworkType.KCP.type) {
            val pathField =
                if (form.network == NetworkType.GRPC.type) ServerField.SERVICE_NAME else ServerField.PATH
            ServerTextField(
                labelRes = when (form.network) {
                    NetworkType.WS.type -> R.string.server_lab_path_ws
                    NetworkType.HTTP_UPGRADE.type -> R.string.server_lab_path_httpupgrade
                    NetworkType.XHTTP.type -> R.string.server_lab_path_xhttp
                    NetworkType.H2.type -> R.string.server_lab_path_h2
                    NetworkType.GRPC.type -> R.string.server_lab_path_grpc
                    else -> R.string.server_lab_path
                },
                field = pathField,
                value = pathField.get(form),
                onAction = onAction,
                fieldErrors = noErrors,
            )
        }

        if (form.network == NetworkType.XHTTP.type) {
            ServerTextField(
                R.string.server_lab_xhttp_extra, ServerField.XHTTP_EXTRA, form.xhttpExtra,
                onAction, noErrors,
            )
        }
        if (form.network == NetworkType.KCP.type) {
            ServerTextField(
                R.string.server_lab_path_kcp, ServerField.SEED, form.seed, onAction, noErrors
            )
            ServerTextField(
                R.string.server_lab_kcp_mtu, ServerField.KCP_MTU, form.kcpMtu,
                onAction, noErrors, KeyboardType.Number,
            )
            ServerTextField(
                R.string.server_lab_kcp_tti, ServerField.KCP_TTI, form.kcpTti,
                onAction, noErrors, KeyboardType.Number,
            )
        }
        ServerTextField(
            R.string.server_lab_final_mask, ServerField.FINAL_MASK, form.finalMask,
            onAction, noErrors,
        )
        if (form.network == NetworkType.WS.type || form.network == NetworkType.XHTTP.type) {
            ServerDropdownField(
                R.string.server_lab_browser_dialer, ServerField.BROWSER_DIALER,
                form.browserDialerMode, options.browserDialer, onAction, noErrors,
            )
        }
    }
}

@Composable
private fun StreamSecurityFields(
    form: ServerForm,
    fieldErrors: Map<ServerField, Int>,
    isFetchingCert: Boolean,
    options: FieldOptions,
    onAction: (ServerAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing)) {
        ServerDropdownField(
            R.string.server_lab_stream_security, ServerField.STREAM_SECURITY,
            form.streamSecurity, options.streamSecurities, onAction, fieldErrors,
        )
        if (form.streamSecurity.isBlank()) return@Column

        ServerTextField(
            R.string.server_lab_sni, ServerField.SNI, form.sni, onAction, fieldErrors
        )
        ServerDropdownField(
            R.string.server_lab_stream_fingerprint, ServerField.FINGERPRINT,
            form.fingerPrint, options.uTls, onAction, fieldErrors,
        )

        when (form.streamSecurity) {
            TLS -> {
                SettingsSwitchItem(
                    title = stringResource(R.string.server_lab_allow_insecure),
                    checked = form.allowInsecure,
                    onCheckedChange = {
                        onAction(ServerAction.FlagChanged(ServerFlag.ALLOW_INSECURE, it))
                    },
                )
                ServerDropdownField(
                    R.string.server_lab_stream_alpn, ServerField.ALPN, form.alpn, options.alpn,
                    onAction, fieldErrors,
                )
                ServerTextField(
                    R.string.server_lab_ech_config_list, ServerField.ECH_CONFIG_LIST,
                    form.echConfigList, onAction, fieldErrors,
                )
                ServerTextField(
                    R.string.server_lab_verify_peer_cert_by_name, ServerField.VERIFY_PEER_CERT,
                    form.verifyPeerCertByName, onAction, fieldErrors,
                )
                PinnedCertFields(form.pinnedCA256, isFetchingCert, onAction)
            }
            REALITY -> {
                ServerTextField(
                    R.string.server_lab_public_key, ServerField.PUBLIC_KEY_REALITY,
                    form.publicKeyReality, onAction, fieldErrors,
                )
                ServerTextField(
                    R.string.server_lab_short_id, ServerField.SHORT_ID, form.shortId,
                    onAction, fieldErrors,
                )
                ServerTextField(
                    R.string.server_lab_spider_x, ServerField.SPIDER_X, form.spiderX,
                    onAction, fieldErrors,
                )
                ServerTextField(
                    R.string.server_lab_mldsa65_verify, ServerField.MLDSA65_VERIFY,
                    form.mldsa65Verify, onAction, fieldErrors,
                )
            }
        }
    }
}
