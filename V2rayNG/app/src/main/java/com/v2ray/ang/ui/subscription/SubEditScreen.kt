package com.v2ray.ang.ui.subscription

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import com.v2ray.ang.ui.components.FormPagedDropdownField
import com.v2ray.ang.ui.components.FormTextField
import com.v2ray.ang.ui.components.NavigationBarsSpacer
import com.v2ray.ang.ui.components.SettingsSwitchItem
import com.v2ray.ang.ui.components.rememberPreviewDropdownItems
import com.v2ray.ang.ui.components.verticalScrollbar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val FormVerticalPad = 8.dp
private val FormBottomGap = 36.dp

@Stable
private class SubEditHost(private val onAction: (SubEditAction) -> Unit) {
    var showDelete by mutableStateOf(false)
        private set

    val requestDelete: (Boolean) -> Unit = { confirmRemove ->
        if (confirmRemove) showDelete = true
        else onAction(SubEditAction.DeleteConfirmed)
    }
    val dismissDelete: () -> Unit = { showDelete = false }
    val confirmDelete: () -> Unit = {
        showDelete = false
        onAction(SubEditAction.DeleteConfirmed)
    }
}

@Composable
fun SubEditScreen(viewModel: SubEditViewModel) {
    val editor = rememberSubEditEditorState(documentKey = viewModel.uiState.value.subId)
    val onAction = remember(viewModel) { viewModel::onAction }
    val host = remember(onAction) { SubEditHost(onAction) }

    val prevItems = viewModel.slices.prevProfiles.collectAsLazyPagingItems()
    val nextItems = viewModel.slices.nextProfiles.collectAsLazyPagingItems()
    val prevQuery by viewModel.slices.prevQuery.collectAsStateWithLifecycle()
    val nextQuery by viewModel.slices.nextQuery.collectAsStateWithLifecycle()
    val isEditFlow = remember(viewModel) {
        viewModel.uiState.map { it.isEdit }.distinctUntilChanged()
    }
    val isReadyFlow = remember(viewModel) {
        viewModel.uiState.map { it.isReady }.distinctUntilChanged()
    }
    val confirmRemoveFlow = remember(viewModel) {
        viewModel.uiState.map { it.confirmRemove }.distinctUntilChanged()
    }

    BaseScreen(
        viewModel = viewModel,
        topBar = {
            val isEdit by isEditFlow.collectAsStateWithLifecycle(
                initialValue = viewModel.uiState.value.isEdit
            )
            val isReady by isReadyFlow.collectAsStateWithLifecycle(
                initialValue = viewModel.uiState.value.isReady
            )
            val confirmRemove by confirmRemoveFlow.collectAsStateWithLifecycle(
                initialValue = viewModel.uiState.value.confirmRemove
            )
            val isMutating by viewModel.isMutating.collectAsStateWithLifecycle()
            SubEditTopBar(
                host = host,
                editor = editor,
                isEdit = isEdit,
                isReady = isReady,
                confirmRemove = confirmRemove,
                isMutating = isMutating,
                onAction = onAction
            )
        }
    ) { state, _ ->
        LaunchedEffect(state.isReady, state.subId) {
            if (state.isReady) state.initialForm?.let(editor::initialize)
        }

        if (state.isReady && editor.text.isInitialized) SubEditFields(
            editor = editor,
            fieldErrors = state.fieldErrors,
            prevItems = prevItems,
            prevQuery = prevQuery,
            onPrevQueryChange = viewModel.slices.onPrevQueryChange,
            nextItems = nextItems,
            nextQuery = nextQuery,
            onNextQueryChange = viewModel.slices.onNextQueryChange,
            modifier = Modifier.fillMaxSize().imePadding()
        )

        if (host.showDelete) {
            DeleteConfirmDialog(
                message = stringResource(R.string.confirm_delete_subscription_group),
                onConfirm = host.confirmDelete,
                onDismiss = host.dismissDelete
            )
        }
    }
}

@Composable
private fun SubEditTopBar(
    host: SubEditHost,
    editor: SubEditEditorState,
    isEdit: Boolean,
    isReady: Boolean,
    confirmRemove: Boolean,
    isMutating: Boolean,
    onAction: (SubEditAction) -> Unit
) {
    AppTopBar(
        title = stringResource(R.string.title_sub_setting),
        onBackClick = { onAction(SubEditAction.Back) },
        isLoading = isMutating,
        actions = {
            if (isEdit) {
                IconButton(
                    onClick = { host.requestDelete(confirmRemove) },
                    enabled = isReady && !isMutating
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = stringResource(R.string.menu_item_del_config)
                    )
                }
            }
            IconButton(
                onClick = { onAction(SubEditAction.Save(editor.snapshot())) },
                enabled = isReady && editor.text.isInitialized && !isMutating
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fab_check),
                    contentDescription = stringResource(R.string.menu_item_save_config)
                )
            }
        }
    )
}

@Composable
private fun SubEditFields(
    editor: SubEditEditorState,
    fieldErrors: Map<SubField, SubEditFieldError>,
    prevItems: LazyPagingItems<DropdownOption>,
    prevQuery: String,
    onPrevQueryChange: (String) -> Unit,
    nextItems: LazyPagingItems<DropdownOption>,
    nextQuery: String,
    onNextQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val remarksError = editor.error(SubField.REMARKS, fieldErrors[SubField.REMARKS])
    val urlError = editor.error(SubField.URL, fieldErrors[SubField.URL])
    val intervalError = editor.error(SubField.UPDATE_INTERVAL, fieldErrors[SubField.UPDATE_INTERVAL])

    // Only the validation boolean is read at this level; field text remains local to the fields.
    val chainSelfReference by remember(editor) {
        derivedStateOf {
            val prev = editor[SubField.PREV_PROFILE].text
            prev.isNotEmpty() && prev.contentEquals(editor[SubField.NEXT_PROFILE].text)
        }
    }
    val chainErrorText = stringResource(R.string.toast_sub_chain_same_profile)

    Column(
        modifier = modifier
            .verticalScrollbar(scrollState)
            .verticalScroll(scrollState)
            .padding(vertical = FormVerticalPad)
    ) {
        FormTextField(
            label = stringResource(R.string.sub_setting_remarks),
            state = editor[SubField.REMARKS],
            isError = remarksError != null,
            supportingText = remarksError?.let { stringResource(it) }
        )
        FormTextField(
            label = stringResource(R.string.sub_setting_url),
            state = editor[SubField.URL],
            isError = urlError != null,
            supportingText = urlError?.let { stringResource(it) }
        )
        FormTextField(
            label = stringResource(R.string.sub_setting_user_agent),
            state = editor[SubField.USER_AGENT]
        )
        FormTextField(
            label = stringResource(R.string.sub_setting_request_headers),
            state = editor[SubField.REQUEST_HEADERS]
        )
        FormTextField(
            label = stringResource(R.string.sub_setting_filter),
            state = editor[SubField.FILTER]
        )
        SettingsSwitchItem(
            title = stringResource(R.string.sub_setting_enable),
            checked = editor.enabled,
            onCheckedChange = { editor.enabled = it }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.sub_auto_update),
            checked = editor.autoUpdate,
            onCheckedChange = { editor.autoUpdate = it }
        )
        FormTextField(
            label = stringResource(R.string.title_pref_auto_update_interval),
            state = editor[SubField.UPDATE_INTERVAL],
            keyboardType = KeyboardType.Number,
            isError = intervalError != null,
            supportingText = intervalError?.let { stringResource(it) }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.sub_allow_insecure_url),
            checked = editor.allowInsecureUrl,
            onCheckedChange = { editor.allowInsecureUrl = it }
        )
        FormPagedDropdownField(
            label = stringResource(R.string.sub_setting_pre_profile),
            placeholder = stringResource(R.string.sub_setting_pre_profile_tip),
            state = editor[SubField.PREV_PROFILE],
            items = prevItems,
            query = prevQuery,
            onQueryChange = onPrevQueryChange,
            isError = chainSelfReference,
            supportingText = if (chainSelfReference) chainErrorText
            else stringResource(R.string.sub_setting_entry_proxy_tip)
        )
        FormPagedDropdownField(
            label = stringResource(R.string.sub_setting_next_profile),
            placeholder = stringResource(R.string.sub_setting_pre_profile_tip),
            state = editor[SubField.NEXT_PROFILE],
            items = nextItems,
            query = nextQuery,
            onQueryChange = onNextQueryChange,
            isError = chainSelfReference,
            supportingText = if (chainSelfReference) chainErrorText
            else stringResource(R.string.sub_setting_exit_proxy_tip)
        )
        Spacer(modifier = Modifier.height(FormBottomGap))
        NavigationBarsSpacer()
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SubEditFieldsPreview() = AppTheme {
    SubEditFields(
        editor = remember {
            SubEditEditorState().apply {
                initialize(SubEditForm(
                    remarks = "Primary",
                    url = "https://example.com/sub",
                    autoUpdate = true,
                    updateInterval = "1440"
                ))
            }
        },
        fieldErrors = emptyMap(),
        prevItems = rememberPreviewDropdownItems(listOf("direct", "proxy")),
        prevQuery = "",
        onPrevQueryChange = {},
        nextItems = rememberPreviewDropdownItems(listOf("direct", "proxy")),
        nextQuery = "",
        onNextQueryChange = {}
    )
}
