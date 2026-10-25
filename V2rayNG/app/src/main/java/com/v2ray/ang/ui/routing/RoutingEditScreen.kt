package com.v2ray.ang.ui.routing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseScreen
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.AppTopBar
import com.v2ray.ang.ui.components.DeleteConfirmDialog
import com.v2ray.ang.ui.components.DropdownOption
import com.v2ray.ang.ui.components.FormDropdownField
import com.v2ray.ang.ui.components.FormPagedDropdownField
import com.v2ray.ang.ui.components.FormTextField
import com.v2ray.ang.ui.components.NavigationBarsSpacer
import com.v2ray.ang.ui.components.SettingsSwitchItem
import com.v2ray.ang.ui.components.StringOptions
import com.v2ray.ang.ui.components.rememberPreviewDropdownItems
import com.v2ray.ang.ui.components.verticalScrollbar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val FormVerticalPadding = 8.dp
private val ButtonStartPadding = 16.dp
private val ButtonIconSpacing = 8.dp
private val BottomSpacerHeight = 36.dp

@Composable
fun RoutingEditScreen(
    viewModel: RoutingEditViewModel,
    modifier: Modifier = Modifier
) {
    val editor = rememberRoutingEditorState(documentKey = viewModel.uiState.value.ruleId)
    val onAction = remember(viewModel) { viewModel::onAction }

    val outboundItems = viewModel.slices.outboundTags.collectAsLazyPagingItems()
    val query by viewModel.slices.query.collectAsStateWithLifecycle()
    val isEditFlow = remember(viewModel) {
        viewModel.uiState.map { it.isEdit }.distinctUntilChanged()
    }
    val isReadyFlow = remember(viewModel) {
        viewModel.uiState.map { it.isReady }.distinctUntilChanged()
    }

    var showDeleteDialog by remember { mutableStateOf(false) }

    BaseScreen(
        viewModel = viewModel,
        modifier = modifier,
        topBar = {
            val isEdit by isEditFlow.collectAsStateWithLifecycle(
                initialValue = viewModel.uiState.value.isEdit
            )
            val isReady by isReadyFlow.collectAsStateWithLifecycle(
                initialValue = viewModel.uiState.value.isReady
            )
            val isMutating by viewModel.isMutating.collectAsStateWithLifecycle()
            RoutingEditTopBar(
                isEdit = isEdit,
                canSave = isReady && editor.text.isInitialized && !isMutating,
                isMutating = isMutating,
                onSave = { onAction(RoutingEditAction.Save(editor.snapshot())) },
                onAction = onAction
            )
        },
        onResult = editor::onPickerResult,
        onEvent = { event ->
            when (event) {
                is RoutingEditEvent.ShowDeleteDialog -> {
                    showDeleteDialog = true
                    true
                }
                else -> false
            }
        }
    ) { state, _ ->
        LaunchedEffect(state.isReady, state.ruleId) {
            if (state.isReady) state.initialForm?.let(editor::initialize)
        }
        if (state.isReady && editor.text.isInitialized) RoutingEditForm(
            state = state,
            editor = editor,
            outboundItems = outboundItems,
            query = query,
            onQueryChange = viewModel.slices.onQueryChange,
            onAction = onAction,
            modifier = Modifier.fillMaxSize().imePadding()
        )
        if (showDeleteDialog) {
            DeleteConfirmDialog(
                message = stringResource(R.string.confirm_delete_routing_rule),
                onConfirm = {
                    showDeleteDialog = false
                    onAction(RoutingEditAction.ConfirmDelete)
                },
                onDismiss = { showDeleteDialog = false }
            )
        }
    }
}

@Composable
private fun RoutingEditTopBar(
    isEdit: Boolean,
    canSave: Boolean,
    isMutating: Boolean,
    onSave: () -> Unit,
    onAction: (RoutingEditAction) -> Unit,
    modifier: Modifier = Modifier
) {
    AppTopBar(
        title = stringResource(R.string.routing_settings_rule_title),
        onBackClick = { onAction(RoutingEditAction.Back) },
        modifier = modifier,
        isLoading = isMutating,
        actions = {
            if (isEdit) {
                IconButton(
                    onClick = { onAction(RoutingEditAction.Delete) },
                    enabled = !isMutating
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = stringResource(R.string.acc_delete)
                    )
                }
            }
            IconButton(onClick = onSave, enabled = canSave) {
                Icon(
                    painter = painterResource(R.drawable.ic_fab_check),
                    contentDescription = stringResource(R.string.acc_save)
                )
            }
        }
    )
}

@Composable
private fun RoutingEditForm(
    state: RoutingEditUiState,
    editor: RoutingEditorState,
    outboundItems: LazyPagingItems<DropdownOption>,
    query: String,
    onQueryChange: (String) -> Unit,
    onAction: (RoutingEditAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val remarksError = editor.error(RoutingField.REMARKS, state.fieldErrors[RoutingField.REMARKS])

    Column(
        modifier = modifier
            .verticalScrollbar(scrollState)
            .verticalScroll(scrollState)
            .padding(vertical = FormVerticalPadding)
    ) {
        FormTextField(
            label = stringResource(R.string.sub_setting_remarks),
            state = editor[RoutingField.REMARKS],
            isError = remarksError != null,
            supportingText = remarksError?.let { stringResource(it) }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.routing_settings_locked),
            checked = editor.locked,
            onCheckedChange = { editor.locked = it }
        )
        FormTextField(
            label = stringResource(R.string.routing_settings_domain),
            placeholder = stringResource(R.string.routing_settings_tips),
            state = editor[RoutingField.DOMAIN]
        )
        FormTextField(
            label = stringResource(R.string.routing_settings_ip),
            placeholder = stringResource(R.string.routing_settings_tips),
            state = editor[RoutingField.IP]
        )
        FormTextField(
            label = stringResource(R.string.routing_settings_process),
            placeholder = stringResource(R.string.routing_settings_tips),
            state = editor[RoutingField.PROCESS],
            enabled = state.canUseProcess
        )
        if (state.canUseProcess) {
            TextButton(
                onClick = { onAction(editor.selectProcessAction()) },
                modifier = Modifier.padding(start = ButtonStartPadding)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_per_apps_24dp),
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(ButtonIconSpacing))
                Text(stringResource(R.string.routing_settings_process_select))
            }
        }
        FormTextField(
            label = stringResource(R.string.routing_settings_port),
            state = editor[RoutingField.PORT]
        )
        FormTextField(
            label = stringResource(R.string.routing_settings_protocol),
            placeholder = stringResource(R.string.routing_settings_protocol_tip),
            state = editor[RoutingField.PROTOCOL]
        )
        FormDropdownField(
            label = stringResource(R.string.routing_settings_network),
            state = editor[RoutingField.NETWORK],
            options = RoutingNetworkOptions
        )
        FormPagedDropdownField(
            label = stringResource(R.string.routing_settings_outbound_tag),
            placeholder = stringResource(
                R.string.routing_settings_outbound_tag_hint,
                stringResource(R.string.server_lab_remarks)
            ),
            state = editor[RoutingField.OUTBOUND],
            items = outboundItems,
            query = query,
            onQueryChange = onQueryChange
        )
        Spacer(modifier = Modifier.height(BottomSpacerHeight))
        NavigationBarsSpacer()
    }
}

private val RoutingNetworkOptions = StringOptions(ROUTING_NETWORK_OPTIONS)

@Preview(showBackground = true)
@Composable
private fun PreviewRoutingEditForm() {
    AppTheme {
        RoutingEditForm(
            state = RoutingEditUiState(
                ruleId = "preview",
                canUseProcess = true
            ),
            editor = remember {
                RoutingEditorState().apply {
                    initialize(RoutingForm(remarks = "Preview", domain = "geosite:google"))
                }
            },
            outboundItems = rememberPreviewDropdownItems(listOf("proxy", "direct")),
            query = "",
            onQueryChange = {},
            onAction = {}
        )
    }
}
