package com.v2ray.ang.ui.userasset

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseScreen
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.AppTopBar
import com.v2ray.ang.ui.components.DeleteConfirmDialog
import com.v2ray.ang.ui.components.FormTextField
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val FormVerticalPad = 8.dp

/**
 * Asset URL editor.
 */
@Composable
fun UserAssetUrlScreen(viewModel: UserAssetUrlViewModel) {
    val editor = rememberAssetEditorState(documentKey = viewModel.uiState.value.assetId)
    val onAction = remember(viewModel) { viewModel::onAction }
    val onBack = remember(onAction) { { onAction(UserAssetUrlAction.Back) } }
    val isReadyFlow = remember(viewModel) {
        viewModel.uiState.map { it.isReady }.distinctUntilChanged()
    }

    BaseScreen(
        viewModel = viewModel,
        topBar = {
            val isEdit by viewModel.isEdit.collectAsStateWithLifecycle()
            val isReady by isReadyFlow
                .collectAsStateWithLifecycle(initialValue = viewModel.uiState.value.isReady)
            val isMutating by viewModel.isMutating.collectAsStateWithLifecycle()
            UserAssetUrlTopBar(
                isEdit = isEdit,
                canSave = isReady && editor.text.isInitialized && !isMutating,
                isMutating = isMutating,
                onSave = { onAction(UserAssetUrlAction.Save(editor.snapshot())) },
                onBack = onBack,
                onAction = onAction
            )
        }
    ) { state, _ ->
        LaunchedEffect(state.isReady, state.assetId) {
            if (state.isReady) state.initialForm?.let(editor::initialize)
        }
        if (state.isReady && editor.text.isInitialized) UserAssetUrlForm(
            editor = editor,
            fieldErrors = state.fieldErrors
        )

        if (state.showDeleteDialog) {
            DeleteConfirmDialog(
                message = stringResource(R.string.confirm_delete_asset_source),
                onConfirm = { onAction(UserAssetUrlAction.DialogConfirm) },
                onDismiss = { onAction(UserAssetUrlAction.DialogDismiss) }
            )
        }
    }
}

@Composable
private fun UserAssetUrlTopBar(
    isEdit: Boolean,
    canSave: Boolean,
    isMutating: Boolean,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onAction: (UserAssetUrlAction) -> Unit,
    modifier: Modifier = Modifier
) {
    AppTopBar(
        title = stringResource(R.string.title_user_asset_add_url),
        onBackClick = onBack,
        modifier = modifier,
        isLoading = isMutating,
        actions = {
            if (isEdit) {
                IconButton(
                    onClick = { onAction(UserAssetUrlAction.DeleteClicked) },
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
private fun UserAssetUrlForm(
    editor: AssetEditorState,
    fieldErrors: Map<AssetField, AssetFieldError>,
    modifier: Modifier = Modifier
) {
    val remarksError = editor.error(AssetField.REMARKS, fieldErrors[AssetField.REMARKS])
    val urlError = editor.error(AssetField.URL, fieldErrors[AssetField.URL])

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(vertical = FormVerticalPad)
    ) {
        FormTextField(
            label = stringResource(R.string.sub_setting_remarks),
            state = editor[AssetField.REMARKS],
            isError = remarksError != null,
            supportingText = remarksError?.let { stringResource(it) }
        )
        FormTextField(
            label = stringResource(R.string.title_url),
            state = editor[AssetField.URL],
            isError = urlError != null,
            supportingText = urlError?.let { stringResource(it) }
        )
    }
}

// ===== previews =====

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UserAssetUrlFormPreview() = AppTheme {
    UserAssetUrlForm(
        editor = remember {
            AssetEditorState().apply {
                initialize(AssetForm("geosite-cn.dat", "https://example.com/geosite-cn.dat"))
            }
        },
        fieldErrors = emptyMap()
    )
}
