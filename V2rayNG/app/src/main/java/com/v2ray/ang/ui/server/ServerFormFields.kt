package com.v2ray.ang.ui.server

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.StringOptions
import com.v2ray.ang.ui.compose.rememberStringOptions

/**
 * Shared dimensions and field helpers used by every server form section.
 *
 * The two form-section files ([ProtocolForm]/[NetworkFields]/[StreamSecurityFields] and
 * [PolicyGroupForm]/[ProxyChainForm]) both depend on this module. Cross-file declarations are
 * `internal` so the sections can reach them without leaking into the public surface.
 */
internal object ServerDimens {
    val FieldSpacing = 8.dp
    val ContentHorizontal = 16.dp
    val ChainIndexWidth = 24.dp
    val ChainRowPadding = 4.dp
    val InlineProgress = 16.dp
    val InlineProgressStroke = 2.dp
    val InlineSpacing = 8.dp
}

/** Renders the inline error message for [field], or null when the field is valid. */
@Composable
internal fun Map<ServerField, Int>.errorMessage(field: ServerField): String? {
    val res = this[field] ?: return null
    return stringResource(res)
}

@Composable
internal fun ServerTextField(
    @StringRes labelRes: Int,
    field: ServerField,
    value: String,
    onAction: (ServerAction) -> Unit,
    fieldErrors: Map<ServerField, Int>,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val error = fieldErrors[field]
    FormTextField(
        label = stringResource(labelRes),
        value = value,
        onValueChange = { onAction(ServerAction.TextChanged(field, it)) },
        keyboardType = keyboardType,
        isError = error != null,
        supportingText = error?.let { stringResource(it) },
    )
}

@Composable
internal fun ServerDropdownField(
    @StringRes labelRes: Int,
    field: ServerField,
    value: String,
    options: StringOptions,
    onAction: (ServerAction) -> Unit,
    fieldErrors: Map<ServerField, Int>,
    editable: Boolean = false,
) {
    val error = fieldErrors[field]
    FormDropdownField(
        label = stringResource(labelRes),
        value = value,
        options = options,
        onValueChange = { onAction(ServerAction.TextChanged(field, it)) },
        editable = editable,
        isError = error != null,
        supportingText = error?.let { stringResource(it) },
    )
}

@Immutable
internal data class FieldOptions(
    val networks: StringOptions,
    val tcpHeaders: StringOptions,
    val kcpHeaders: StringOptions,
    val grpcModes: StringOptions,
    val xhttpModes: StringOptions,
    val streamSecurities: StringOptions,
    val uTls: StringOptions,
    val alpn: StringOptions,
    val browserDialer: StringOptions,
    val vmessSecurities: StringOptions,
    val ssSecurities: StringOptions,
    val flows: StringOptions,
    val policyGroupTypes: StringOptions,
)

@Composable
internal fun rememberFieldOptions(): FieldOptions = FieldOptions(
    networks = rememberStringOptions(R.array.networks),
    tcpHeaders = rememberStringOptions(R.array.header_type_tcp),
    kcpHeaders = rememberStringOptions(R.array.header_type_kcp_and_quic),
    grpcModes = rememberStringOptions(R.array.mode_type_grpc),
    xhttpModes = rememberStringOptions(R.array.xhttp_mode),
    streamSecurities = rememberStringOptions(R.array.streamsecurityxs),
    uTls = rememberStringOptions(R.array.streamsecurity_utls),
    alpn = rememberStringOptions(R.array.streamsecurity_alpn),
    browserDialer = rememberStringOptions(R.array.browser_dialer_mode_value),
    vmessSecurities = rememberStringOptions(R.array.securitys),
    ssSecurities = rememberStringOptions(R.array.ss_securitys),
    flows = rememberStringOptions(R.array.flows),
    policyGroupTypes = rememberStringOptions(R.array.policy_group_type),
)
