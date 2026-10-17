package com.v2ray.ang.ui.server

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.components.FieldHorizontalPad
import com.v2ray.ang.ui.components.FieldVerticalPad
import com.v2ray.ang.ui.components.FormDropdownField
import com.v2ray.ang.ui.components.FormTextField
import com.v2ray.ang.ui.components.StringOptions
import com.v2ray.ang.ui.components.appFieldColors
import com.v2ray.ang.ui.components.rememberStringOptions
import com.v2ray.ang.ui.components.verticalScrollbar

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
    editor: ServerEditorState,
    fieldErrors: Map<ServerField, Int>,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val error = fieldErrors[field]
    FormTextField(
        label = stringResource(labelRes),
        state = editor[field],
        keyboardType = keyboardType,
        isError = error != null,
        supportingText = error?.let { stringResource(it) }
    )
}

@Composable
internal fun ServerDropdownField(
    @StringRes labelRes: Int,
    field: ServerField,
    editor: ServerEditorState,
    options: StringOptions,
    fieldErrors: Map<ServerField, Int>,
    editable: Boolean = false
) {
    val error = fieldErrors[field]
    FormDropdownField(
        label = stringResource(labelRes),
        state = editor[field],
        options = options,
        editable = editable,
        isError = error != null,
        supportingText = error?.let { stringResource(it) }
    )
}

/** Store stable IDs directly; localized labels are a display transformation, never another draft. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerIdDropdownField(
    label: String,
    state: TextFieldState,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val value = state.text.toString()
    val displayLabel = options.firstOrNull { it.first == value }?.second ?: value
    val outputTransformation = remember(displayLabel) {
        OutputTransformation { replace(0, length, displayLabel) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FieldHorizontalPad, vertical = FieldVerticalPad)
    ) {
        OutlinedTextField(
            state = state,
            readOnly = true,
            lineLimits = TextFieldLineLimits.SingleLine,
            outputTransformation = outputTransformation,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = appFieldColors(),
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            scrollState = scrollState,
            modifier = Modifier.verticalScrollbar(scrollState),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        state.setTextAndPlaceCursorAtEnd(id)
                        expanded = false
                        focusManager.clearFocus()
                    }
                )
            }
        }
    }
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
    val xdriveServices: StringOptions,
    val policyGroupTypes: StringOptions
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
    xdriveServices = rememberStringOptions(R.array.xdrive_services),
    policyGroupTypes = rememberStringOptions(R.array.policy_group_type)
)
