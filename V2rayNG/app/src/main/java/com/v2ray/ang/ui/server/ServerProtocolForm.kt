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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_GOOGLE_DRIVE
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_TEMPLATE
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.TLS
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.ui.components.FormTextField
import com.v2ray.ang.ui.components.SettingsSwitchItem
import com.v2ray.ang.ui.components.StringOptions

private data class ProtocolSpec(
    val showNetwork: Boolean = false,
    val showStreamSecurity: Boolean = false
)

private fun EConfigType.spec(): ProtocolSpec = when (this) {
    EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN, EConfigType.SHADOWSOCKS ->
        ProtocolSpec(showNetwork = true, showStreamSecurity = true)
    EConfigType.MASQUE -> ProtocolSpec(showStreamSecurity = true)
    else -> ProtocolSpec()
}

@Composable
internal fun ProtocolForm(
    configType: EConfigType,
    editor: ServerEditorState,
    fieldErrors: Map<ServerField, Int>,
    isFetchingCert: Boolean,
    onFetchCertificate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val options = rememberFieldOptions()
    val spec = remember(configType) { configType.spec() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing)
    ) {
        ServerTextField(
            R.string.server_lab_remarks, ServerField.REMARKS, editor, fieldErrors
        )
        ServerTextField(
            R.string.server_lab_address, ServerField.ADDRESS, editor, fieldErrors
        )
        ServerTextField(
            R.string.server_lab_port, ServerField.PORT, editor, fieldErrors,
            KeyboardType.Number
        )

        ProtocolFields(configType, editor, fieldErrors, isFetchingCert, options, onFetchCertificate)

        if (configType == EConfigType.MASQUE) {
            MasqueFields(editor, fieldErrors)
        }

        if (spec.showNetwork) NetworkFields(configType, editor, fieldErrors, options)
        if (spec.showStreamSecurity) {
            StreamSecurityFields(configType, editor, fieldErrors, isFetchingCert, options, onFetchCertificate)
        }
    }
}

@Composable
private fun ProtocolFields(
    configType: EConfigType,
    editor: ServerEditorState,
    fieldErrors: Map<ServerField, Int>,
    isFetchingCert: Boolean,
    options: FieldOptions,
    onFetchCertificate: () -> Unit
) {
    val network by remember(editor) {
        derivedStateOf { editor[ServerField.NETWORK].text.toString() }
    }
    when (configType) {
        EConfigType.VMESS -> {
            ServerTextField(
                R.string.server_lab_id, ServerField.PASSWORD, editor, fieldErrors
            )
            ServerDropdownField(
                R.string.server_lab_security, ServerField.METHOD, editor, options.vmessSecurities, fieldErrors
            )
        }
        EConfigType.VLESS -> {
            ServerTextField(
                R.string.server_lab_id, ServerField.PASSWORD, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_encryption, ServerField.ENCRYPTION, editor, fieldErrors
            )
            if (network != NetworkType.XDRIVE.type) {
                ServerDropdownField(
                    R.string.server_lab_flow, ServerField.FLOW, editor, options.flows, fieldErrors
                )
            }
        }
        EConfigType.TROJAN ->
            ServerTextField(
                R.string.server_lab_id3, ServerField.PASSWORD, editor, fieldErrors
            )
        EConfigType.MASQUE -> {
            ServerTextField(
                R.string.server_lab_masque_user, ServerField.USERNAME, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_masque_password, ServerField.PASSWORD, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_remote_dns, ServerField.REMOTE_DNS, editor, fieldErrors
            )
        }
        EConfigType.SHADOWSOCKS -> {
            ServerTextField(
                R.string.server_lab_id3, ServerField.PASSWORD, editor, fieldErrors
            )
            ServerDropdownField(
                R.string.server_lab_security, ServerField.METHOD, editor, options.ssSecurities, fieldErrors
            )
        }
        EConfigType.WIREGUARD -> {
            ServerTextField(
                R.string.server_lab_secret_key, ServerField.SECRET_KEY, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_public_key, ServerField.PUBLIC_KEY, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_preshared_key, ServerField.PRE_SHARED_KEY, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_reserved, ServerField.RESERVED, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_local_address, ServerField.LOCAL_ADDRESS, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_local_mtu, ServerField.MTU, editor, fieldErrors, KeyboardType.Number
            )
            ServerTextField(
                R.string.server_lab_remote_dns, ServerField.REMOTE_DNS, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_final_mask, ServerField.FINAL_MASK, editor, fieldErrors
            )
        }
        EConfigType.HYSTERIA2 -> {
            ServerTextField(
                R.string.server_lab_id3, ServerField.PASSWORD, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_obfs_password, ServerField.OBFS_PASSWORD, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_port_hop, ServerField.PORT_HOPPING, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_port_hop_interval, ServerField.PORT_HOPPING_INTERVAL,
                editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_bandwidth_down, ServerField.BANDWIDTH_DOWN,
                editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_bandwidth_up, ServerField.BANDWIDTH_UP,
                editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_final_mask, ServerField.FINAL_MASK, editor, fieldErrors
            )
            SettingsSwitchItem(
                title = stringResource(R.string.server_lab_allow_insecure),
                checked = editor.allowInsecure,
                onCheckedChange = { editor.allowInsecure = it }
            )
            ServerTextField(
                R.string.server_lab_sni, ServerField.SNI, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_ech_config_list, ServerField.ECH_CONFIG_LIST,
                editor, fieldErrors
            )
            PinnedCertFields(editor, isFetchingCert, onFetchCertificate)
        }
        else -> {
            ServerTextField(
                R.string.server_lab_security4, ServerField.USERNAME, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_id4, ServerField.PASSWORD, editor, fieldErrors
            )
        }
    }
}

@Composable
private fun MasqueFields(
    editor: ServerEditorState,
    fieldErrors: Map<ServerField, Int>
) {
    Column(verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing)) {
        ServerTextField(
            R.string.server_lab_masque_host, ServerField.HOST, editor, fieldErrors
        )
        ServerTextField(
            R.string.server_lab_masque_path, ServerField.PATH, editor, fieldErrors
        )
        ServerTextField(
            R.string.server_lab_masque_headers, ServerField.MASQUE_HEADERS,
            editor, fieldErrors
        )
    }
}

@Composable
internal fun PinnedCertFields(
    editor: ServerEditorState,
    isFetchingCert: Boolean,
    onFetchCertificate: () -> Unit
) {
    FormTextField(
        label = stringResource(R.string.server_lab_pinned_ca256),
        state = editor[ServerField.PINNED_CA256]
    )
    Button(
        onClick = onFetchCertificate,
        enabled = !isFetchingCert,
        modifier = Modifier.padding(horizontal = ServerDimens.ContentHorizontal)
    ) {
        if (isFetchingCert) {
            CircularProgressIndicator(
                modifier = Modifier.size(ServerDimens.InlineProgress),
                strokeWidth = ServerDimens.InlineProgressStroke,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(ServerDimens.InlineSpacing))
        }
        Text(stringResource(R.string.pinned_ca256_action_fetch))
    }
}

@Composable
private fun NetworkFields(
    configType: EConfigType,
    editor: ServerEditorState,
    fieldErrors: Map<ServerField, Int>,
    options: FieldOptions
) {
    val network by remember(editor) {
        derivedStateOf { editor[ServerField.NETWORK].text.toString() }
    }
    val service by remember(editor) {
        derivedStateOf { editor[ServerField.SERVICE].text.toString() }
    }
    val networkOptions = if (configType == EConfigType.VLESS) {
        options.networks
    } else {
        StringOptions(options.networks.values.filter { it != NetworkType.XDRIVE.type })
    }
    Column(verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing)) {
        ServerDropdownField(
            R.string.server_lab_network, ServerField.NETWORK, editor, networkOptions, fieldErrors
        )

        if (network == NetworkType.XDRIVE.type) {
            ServerDropdownField(
                R.string.server_lab_xdrive_service, ServerField.SERVICE, editor, options.xdriveServices, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_xdrive_remote_folder, ServerField.REMOTE_FOLDER,
                editor, fieldErrors
            )
            if (service == XDRIVE_SERVICE_GOOGLE_DRIVE || service == XDRIVE_SERVICE_TEMPLATE) {
                ServerTextField(
                    R.string.server_lab_xdrive_secrets, ServerField.SECRETS, editor, fieldErrors
                )
            }
            if (service == XDRIVE_SERVICE_TEMPLATE) {
                ServerTextField(
                    R.string.server_lab_xdrive_template, ServerField.TEMPLATE,
                    editor, fieldErrors
                )
            }
            return@Column
        }

        val headerOptions = when (network) {
            NetworkType.TCP.type -> options.tcpHeaders
            NetworkType.KCP.type -> options.kcpHeaders
            NetworkType.GRPC.type -> options.grpcModes
            NetworkType.XHTTP.type -> options.xhttpModes
            else -> null
        }
        if (headerOptions != null && headerOptions.size > 1) {
            val headerField = when (network) {
                NetworkType.GRPC.type -> ServerField.MODE
                NetworkType.XHTTP.type -> ServerField.XHTTP_MODE
                else -> ServerField.HEADER_TYPE
            }
            ServerDropdownField(
                labelRes = when (network) {
                    NetworkType.GRPC.type -> R.string.server_lab_mode_type
                    NetworkType.XHTTP.type -> R.string.server_lab_xhttp_mode
                    else -> R.string.server_lab_head_type
                },
                field = headerField,
                editor = editor,
                options = headerOptions,
                fieldErrors = fieldErrors
            )
        }

        val hostField =
            if (network == NetworkType.GRPC.type) ServerField.AUTHORITY else ServerField.HOST
        ServerTextField(
            labelRes = when (network) {
                NetworkType.TCP.type,
                NetworkType.HTTP_UPGRADE.type,
                NetworkType.XHTTP.type,
                NetworkType.H2.type -> R.string.server_lab_request_host_http
                NetworkType.WS.type -> R.string.server_lab_request_host_ws
                NetworkType.GRPC.type -> R.string.server_lab_request_host_grpc
                else -> R.string.server_lab_request_host6
            },
            field = hostField,
            editor = editor,
            fieldErrors = fieldErrors
        )

        if (network != NetworkType.KCP.type) {
            val pathField =
                if (network == NetworkType.GRPC.type) ServerField.SERVICE_NAME else ServerField.PATH
            ServerTextField(
                labelRes = when (network) {
                    NetworkType.WS.type -> R.string.server_lab_path_ws
                    NetworkType.HTTP_UPGRADE.type -> R.string.server_lab_path_httpupgrade
                    NetworkType.XHTTP.type -> R.string.server_lab_path_xhttp
                    NetworkType.H2.type -> R.string.server_lab_path_h2
                    NetworkType.GRPC.type -> R.string.server_lab_path_grpc
                    else -> R.string.server_lab_path
                },
                field = pathField,
                editor = editor,
                fieldErrors = fieldErrors
            )
        }

        if (network == NetworkType.XHTTP.type) {
            ServerTextField(
                R.string.server_lab_xhttp_extra, ServerField.XHTTP_EXTRA, editor, fieldErrors
            )
        }
        if (network == NetworkType.KCP.type) {
            ServerTextField(
                R.string.server_lab_path_kcp, ServerField.SEED, editor, fieldErrors
            )
            ServerTextField(
                R.string.server_lab_kcp_mtu, ServerField.KCP_MTU, editor, fieldErrors, KeyboardType.Number
            )
            ServerTextField(
                R.string.server_lab_kcp_tti, ServerField.KCP_TTI, editor, fieldErrors, KeyboardType.Number
            )
        }
        ServerTextField(
            R.string.server_lab_final_mask, ServerField.FINAL_MASK, editor, fieldErrors
        )
        if (network == NetworkType.WS.type || network == NetworkType.XHTTP.type) {
            ServerDropdownField(
                R.string.server_lab_browser_dialer, ServerField.BROWSER_DIALER,
                editor, options.browserDialer, fieldErrors
            )
        }
    }
}

@Composable
private fun StreamSecurityFields(
    configType: EConfigType,
    editor: ServerEditorState,
    fieldErrors: Map<ServerField, Int>,
    isFetchingCert: Boolean,
    options: FieldOptions,
    onFetchCertificate: () -> Unit
) {
    val streamSecurity by remember(editor) {
        derivedStateOf { editor[ServerField.STREAM_SECURITY].text.toString() }
    }
    val streamSecurityOptions = if (configType == EConfigType.MASQUE) {
        StringOptions(listOf(TLS))
    } else {
        options.streamSecurities
    }
    Column(verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing)) {
        ServerDropdownField(
            R.string.server_lab_stream_security, ServerField.STREAM_SECURITY,
            editor, streamSecurityOptions, fieldErrors
        )
        if (streamSecurity.isBlank()) return@Column

        ServerTextField(
            R.string.server_lab_sni, ServerField.SNI, editor, fieldErrors
        )
        ServerDropdownField(
            R.string.server_lab_stream_fingerprint, ServerField.FINGERPRINT,
            editor, options.uTls, fieldErrors
        )

        when (streamSecurity) {
            TLS -> {
                if (configType != EConfigType.MASQUE) {
                    SettingsSwitchItem(
                        title = stringResource(R.string.server_lab_allow_insecure),
                        checked = editor.allowInsecure,
                        onCheckedChange = { editor.allowInsecure = it }
                    )
                }
                ServerDropdownField(
                    R.string.server_lab_stream_alpn, ServerField.ALPN, editor, options.alpn, fieldErrors
                )
                ServerTextField(
                    R.string.server_lab_ech_config_list, ServerField.ECH_CONFIG_LIST,
                    editor, fieldErrors
                )
                ServerTextField(
                    R.string.server_lab_verify_peer_cert_by_name, ServerField.VERIFY_PEER_CERT,
                    editor, fieldErrors
                )
                PinnedCertFields(editor, isFetchingCert, onFetchCertificate)
            }
            REALITY -> {
                ServerTextField(
                    R.string.server_lab_public_key, ServerField.PUBLIC_KEY_REALITY,
                    editor, fieldErrors
                )
                ServerTextField(
                    R.string.server_lab_short_id, ServerField.SHORT_ID, editor, fieldErrors
                )
                ServerTextField(
                    R.string.server_lab_spider_x, ServerField.SPIDER_X, editor, fieldErrors
                )
                ServerTextField(
                    R.string.server_lab_mldsa65_verify, ServerField.MLDSA65_VERIFY,
                    editor, fieldErrors
                )
            }
        }
    }
}
