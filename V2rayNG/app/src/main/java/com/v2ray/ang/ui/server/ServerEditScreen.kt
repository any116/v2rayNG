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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.BaseScreen
import com.v2ray.ang.ui.components.AppTopBar
import com.v2ray.ang.ui.components.DeleteConfirmDialog
import com.v2ray.ang.ui.components.NavigationBarsBottomPadding
import com.v2ray.ang.ui.components.NavigationBarsSpacer
import com.v2ray.ang.ui.components.verticalScrollbar

private val ChainMemberFabOffsetY = (-28).dp

@Composable
fun ServerEditScreen(viewModel: ServerEditViewModel) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val editor = rememberServerEditorState(viewModel.documentKey)
    var confirmDelete by key(viewModel.documentKey) {
        rememberSaveable { mutableStateOf(false) }
    }
    var pendingRemovalId by key(viewModel.documentKey) {
        rememberSaveable { mutableStateOf<String?>(null) }
    }

    val slices = viewModel.slices
    val tagQuery by slices.tagQuery.collectAsStateWithLifecycle()

    val onAction = remember(viewModel) { viewModel::onAction }
    val onFetchCertificate: () -> Unit = remember(viewModel, editor) {
        {
            if (editor.isInitialized) {
                onAction(ServerAction.FetchCertificate(editor.snapshot().form))
            }
        }
    }
    val onRemoveChainMember: (String) -> Unit = remember(editor) {
        { id ->
            if (editor.chainMemberNeedsConfirmation(id)) pendingRemovalId = id
            else editor.removeChainMember(id)
        }
    }

    BaseScreen(
        viewModel = viewModel,
        topBar = {
            val isMutating by viewModel.isMutating.collectAsStateWithLifecycle()
            ServerEditTopBar(
                header = header,
                editor = editor,
                isMutating = isMutating,
                onAction = onAction
            )
        },
        floatingActionButton = {
            if (header.isLoaded) ChainMemberFab(configType = header.configType, editor = editor)
        },
        onEvent = { event ->
            when (event) {
                ServerEvent.ConfirmDeleteProfile -> {
                    confirmDelete = true
                    true
                }
                is ServerEvent.CertificateFetched -> {
                    editor.applyCertificate(event.pinnedCA256)
                    true
                }
                else -> false
            }
        }
    ) { state, _ ->
        LaunchedEffect(editor, state.isLoaded) {
            if (state.isLoaded) state.initialForm?.let { editor.initialize(it, state.initialRawContent) }
        }

        val visibleErrors by remember(editor, state.fieldErrors) {
            derivedStateOf { editor.visibleErrors(state.fieldErrors) }
        }
        if (state.isLoaded && editor.isInitialized) ServerEditContent(
            configType = state.configType,
            editor = editor,
            fieldErrors = visibleErrors,
            options = state.options,
            isFetchingCert = state.isFetchingCert,
            tagQuery = tagQuery,
            onTagQueryChange = slices.onTagQueryChange,
            slices = slices,
            onFetchCertificate = onFetchCertificate,
            onRemoveChainMember = onRemoveChainMember,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        )

        ServerEditDialogs(
            confirmDelete = confirmDelete,
            pendingRemovalId = pendingRemovalId,
            configType = state.configType,
            onAction = onAction,
            onRemoveChainMember = editor::removeChainMember,
            onDismiss = {
                confirmDelete = false
                pendingRemovalId = null
            }
        )
    }
}

@Composable
private fun ServerEditTopBar(
    header: ServerHeader,
    editor: ServerEditorState,
    isMutating: Boolean,
    onAction: (ServerAction) -> Unit
) {
    AppTopBar(
        title = header.configType.toString(),
        onBackClick = { onAction(ServerAction.Back) },
        isLoading = isMutating,
        actions = {
            if (header.canDelete) {
                IconButton(
                    onClick = { onAction(ServerAction.DeleteClicked) },
                    enabled = !isMutating
                ) {
                    Icon(
                        painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = stringResource(R.string.menu_item_del_config)
                    )
                }
            }
            IconButton(
                enabled = header.isLoaded && editor.isInitialized && !isMutating,
                onClick = {
                    if (header.isLoaded && editor.isInitialized) {
                        val snapshot = editor.snapshot()
                        onAction(ServerAction.Save(snapshot.form, snapshot.rawContent))
                    }
                }
            ) {
                Icon(
                    painterResource(R.drawable.ic_fab_check),
                    contentDescription = stringResource(R.string.menu_item_save_config)
                )
            }
        }
    )
}

@Composable
private fun ChainMemberFab(configType: EConfigType, editor: ServerEditorState) {
    if (configType != EConfigType.PROXYCHAIN || !editor.isInitialized) return
    FloatingActionButton(
        onClick = editor::addChainMember,
        modifier = Modifier
            .offset(y = ChainMemberFabOffsetY)
            .navigationBarsPadding()
    ) {
        Icon(
            painterResource(R.drawable.ic_add_24dp),
            contentDescription = stringResource(R.string.server_proxy_chain_members)
        )
    }
}

@Composable
private fun ServerEditDialogs(
    confirmDelete: Boolean,
    pendingRemovalId: String?,
    configType: EConfigType,
    onAction: (ServerAction) -> Unit,
    onRemoveChainMember: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when {
        confirmDelete -> DeleteConfirmDialog(
            message = stringResource(
                if (configType == EConfigType.POLICYGROUP) R.string.confirm_delete_policy_group
                else R.string.confirm_delete_profile
            ),
            onConfirm = {
                onAction(ServerAction.ConfirmDeleteProfile)
                onDismiss()
            },
            onDismiss = onDismiss
        )

        pendingRemovalId != null -> DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_proxy_chain_member),
            onConfirm = {
                onRemoveChainMember(pendingRemovalId)
                onDismiss()
            },
            onDismiss = onDismiss
        )

        else -> Unit
    }
}

@Composable
private fun ServerEditContent(
    configType: EConfigType,
    editor: ServerEditorState,
    fieldErrors: Map<ServerField, Int>,
    options: ServerOptions,
    isFetchingCert: Boolean,
    tagQuery: String,
    onTagQueryChange: (String) -> Unit,
    slices: ServerSlices,
    onFetchCertificate: () -> Unit,
    onRemoveChainMember: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        when (configType) {
            EConfigType.CUSTOM -> {
                ServerJsonEditor(
                    remarks = editor[ServerField.REMARKS],
                    remarksError = fieldErrors[ServerField.REMARKS],
                    rawContent = editor.rawContent,
                    modifier = Modifier.weight(1f)
                )
            }

            EConfigType.PROXYCHAIN -> {
                ProxyChainForm(
                    editor = editor,
                    remarksError = fieldErrors[ServerField.REMARKS],
                    chainSlice = slices.chainSlice,
                    onRetainMembers = slices.retainChainSlices,
                    onRemoveChainMember = onRemoveChainMember,
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
                        .padding(top = 8.dp)
                ) {
                    PolicyGroupForm(
                        editor = editor,
                        remarksError = fieldErrors[ServerField.REMARKS],
                        options = options,
                        fallbackTags = slices.fallbackTags,
                        tagQuery = tagQuery,
                        onTagQueryChange = onTagQueryChange
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
                        .padding(top = 8.dp)
                ) {
                    ProtocolForm(
                        configType = configType,
                        editor = editor,
                        fieldErrors = fieldErrors,
                        isFetchingCert = isFetchingCert,
                        onFetchCertificate = onFetchCertificate
                    )
                    Spacer(modifier = Modifier.height(36.dp))
                    NavigationBarsSpacer()
                }
            }
        }
    }
}
