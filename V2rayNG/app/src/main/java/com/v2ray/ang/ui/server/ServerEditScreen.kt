package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.BaseScreen
import com.v2ray.ang.ui.components.AppTopBar
import com.v2ray.ang.ui.components.DeleteConfirmDialog
import com.v2ray.ang.ui.components.DropdownOption
import com.v2ray.ang.ui.components.rememberSynchronizedTextFieldState
import com.v2ray.ang.ui.components.NavigationBarsBottomPadding
import com.v2ray.ang.ui.components.NavigationBarsSpacer
import com.v2ray.ang.ui.components.rememberTextFieldStateRegistry
import com.v2ray.ang.ui.components.verticalScrollbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RawContentSyncDebounceMs = 250L
private val ChainMemberFabOffsetY = (-28).dp

@Composable
fun ServerEditScreen(viewModel: ServerEditViewModel) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    var pendingDialog by rememberSaveable { mutableStateOf<ServerDialog?>(null) }

    val slices = viewModel.slices
    val chainCandidates = slices.chainCandidates.collectAsLazyPagingItems()
    val fallbackTags = slices.fallbackTags.collectAsLazyPagingItems()
    val chainQuery by slices.chainQuery.collectAsStateWithLifecycle()
    val tagQuery by slices.tagQuery.collectAsStateWithLifecycle()

    val textFieldStateRegistry = rememberTextFieldStateRegistry()
    val onAction: (ServerAction) -> Unit = remember(viewModel, textFieldStateRegistry) {
        { action ->
            if (action is ServerAction.Save || action is ServerAction.FetchCertificate) {
                textFieldStateRegistry.flush()
            }
            viewModel.onAction(action)
        }
    }

    BaseScreen(
        viewModel = viewModel,
        textFieldStateRegistry = textFieldStateRegistry,
        topBar = { ServerEditTopBar(header = header, onAction = onAction) },
        floatingActionButton = { ChainMemberFab(configType = header.configType, onAction = onAction) },
        onEvent = { event ->
            when (event) {
                ServerEvent.ConfirmDeleteProfile -> {
                    pendingDialog = ServerDialog.DeleteProfile
                    true
                }
                is ServerEvent.ConfirmRemoveChainMember -> {
                    pendingDialog = ServerDialog.RemoveChainMember(event.id)
                    true
                }
                else -> false
            }
        },
    ) { state, _ ->
        val rawContent = rememberSyncedRawContent(
            guid = state.guid,
            externalValue = state.rawContent,
            onAction = onAction,
        )

        ServerEditContent(
            configType = state.configType,
            form = state.form,
            fieldErrors = state.fieldErrors,
            options = state.options,
            isFetchingCert = state.isFetchingCert,
            rawContent = rawContent,
            chainCandidates = chainCandidates,
            fallbackTags = fallbackTags,
            chainQuery = chainQuery,
            tagQuery = tagQuery,
            onChainQueryChange = slices.onChainQueryChange,
            onTagQueryChange = slices.onTagQueryChange,
            onAction = onAction,
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        )

        ServerEditDialogs(
            dialog = pendingDialog,
            configType = state.configType,
            onAction = onAction,
            onDismiss = { pendingDialog = null },
        )
    }
}

@Composable
private fun rememberSyncedRawContent(
    guid: String,
    externalValue: String,
    onAction: (ServerAction) -> Unit,
): TextFieldState {
    val scope = rememberCoroutineScope()
    var rawJob by remember(guid) { mutableStateOf<Job?>(null) }
    val currentOnAction by rememberUpdatedState(onAction)
    var loadedGuid by remember { mutableStateOf<String?>(null) }

    val scheduleRawChange: (String) -> Unit = { text ->
        rawJob?.cancel()
        rawJob = scope.launch {
            delay(RawContentSyncDebounceMs)
            currentOnAction(ServerAction.RawContentChanged(text))
        }
    }
    val flushRawChange: (String) -> Unit = { text ->
        rawJob?.cancel()
        currentOnAction(ServerAction.RawContentChanged(text))
    }

    return rememberSynchronizedTextFieldState(
        value = externalValue,
        onValueChange = scheduleRawChange,
        onFlush = flushRawChange,
        setExternalValue = { state, text ->
            val isNewDocument = loadedGuid != guid
            val caret = state.selection.start.coerceAtMost(text.length)
            state.edit {
                replace(0, length, text)
                selection = if (isNewDocument) TextRange(0) else TextRange(caret)
            }
            loadedGuid = guid
        }
    )
}

@Composable
private fun ServerEditTopBar(header: ServerHeader, onAction: (ServerAction) -> Unit) {
    AppTopBar(
        title = header.configType.toString(),
        onBackClick = { onAction(ServerAction.Back) },
        actions = {
            if (header.canDelete) {
                IconButton(onClick = { onAction(ServerAction.DeleteClicked) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = stringResource(R.string.menu_item_del_config),
                    )
                }
            }
            IconButton(onClick = { onAction(ServerAction.Save) }) {
                Icon(
                    painterResource(R.drawable.ic_fab_check),
                    contentDescription = stringResource(R.string.menu_item_save_config),
                )
            }
        },
    )
}

@Composable
private fun ChainMemberFab(configType: EConfigType, onAction: (ServerAction) -> Unit) {
    if (configType != EConfigType.PROXYCHAIN) return
    FloatingActionButton(
        onClick = { onAction(ServerAction.AddChainMember) },
        modifier = Modifier
            .offset(y = ChainMemberFabOffsetY)
            .navigationBarsPadding(),
    ) {
        Icon(
            painterResource(R.drawable.ic_add_24dp),
            contentDescription = stringResource(R.string.server_proxy_chain_members),
        )
    }
}

@Composable
private fun ServerEditDialogs(
    dialog: ServerDialog?,
    configType: EConfigType,
    onAction: (ServerAction) -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        ServerDialog.DeleteProfile -> DeleteConfirmDialog(
            message = stringResource(
                if (configType == EConfigType.POLICYGROUP) R.string.confirm_delete_policy_group
                else R.string.confirm_delete_profile
            ),
            onConfirm = {
                onAction(ServerAction.ConfirmDeleteProfile)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is ServerDialog.RemoveChainMember -> DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_proxy_chain_member),
            onConfirm = {
                onAction(ServerAction.ConfirmRemoveChainMember(dialog.id))
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        null -> Unit
    }
}

@Composable
private fun ServerEditContent(
    configType: EConfigType,
    form: ServerForm,
    fieldErrors: Map<ServerField, Int>,
    options: ServerOptions,
    isFetchingCert: Boolean,
    rawContent: TextFieldState,
    chainCandidates: LazyPagingItems<DropdownOption>,
    fallbackTags: LazyPagingItems<DropdownOption>,
    chainQuery: String,
    tagQuery: String,
    onChainQueryChange: (String) -> Unit,
    onTagQueryChange: (String) -> Unit,
    onAction: (ServerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        when (configType) {
            EConfigType.CUSTOM -> {
                ServerJsonEditor(
                    remarks = form.remarks,
                    rawContent = rawContent,
                    onAction = onAction,
                    modifier = Modifier.weight(1f),
                )
            }

            EConfigType.PROXYCHAIN -> {
                ProxyChainForm(
                    remarks = form.remarks,
                    remarksError = fieldErrors[ServerField.REMARKS],
                    members = form.chainMembers,
                    candidates = chainCandidates,
                    chainQuery = chainQuery,
                    onChainQueryChange = onChainQueryChange,
                    onAction = onAction,
                    modifier = Modifier.weight(1f),
                    contentPadding = NavigationBarsBottomPadding(extra = 64.dp)
                )
            }

            EConfigType.POLICYGROUP -> {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScrollbar(scrollState)
                        .verticalScroll(scrollState)
                        .padding(top = 8.dp),
                ) {
                    PolicyGroupForm(
                        form = form,
                        remarksError = fieldErrors[ServerField.REMARKS],
                        options = options,
                        fallbackTags = fallbackTags,
                        tagQuery = tagQuery,
                        onTagQueryChange = onTagQueryChange,
                        onAction = onAction,
                    )
                    NavigationBarsSpacer()
                }
            }

            else -> {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScrollbar(scrollState)
                        .verticalScroll(scrollState)
                        .padding(top = 8.dp),
                ) {
                    ProtocolForm(
                        configType = configType,
                        form = form,
                        fieldErrors = fieldErrors,
                        isFetchingCert = isFetchingCert,
                        onAction = onAction,
                    )
                    Spacer(modifier = Modifier.height(36.dp))
                    NavigationBarsSpacer()
                }
            }
        }
    }
}
