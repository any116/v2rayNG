package com.v2ray.ang.ui.main

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.ServerRowItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.components.DeleteConfirmDialog
import com.v2ray.ang.ui.components.QRCodeDialog

@Immutable
sealed interface MainDialog {
    data object DeleteAll : MainDialog
    data object DeleteDuplicate : MainDialog
    data object DeleteInvalid : MainDialog
    data class DeleteOne(val row: ServerRowItem) : MainDialog
    data class Share(
        val row: ServerRowItem,
        val includeManagement: Boolean,
    ) : MainDialog
    data class QrCode(val bitmap: Bitmap) : MainDialog
}

@Composable
fun MainDialogs(
    dialog: MainDialog?,
    onDismiss: () -> Unit,
    onAction: (MainAction) -> Unit,
    onRequestRemove: (ServerRowItem) -> Unit,
) = when (dialog) {
    null -> Unit
    MainDialog.DeleteAll -> DeleteConfirmDialog(
        message = stringResource(R.string.confirm_delete_visible_profiles),
        onConfirm = { onAction(MainAction.RemoveAllServers) },
        onDismiss = onDismiss,
    )
    MainDialog.DeleteDuplicate -> DeleteConfirmDialog(
        message = stringResource(R.string.confirm_delete_duplicate_profiles),
        onConfirm = { onAction(MainAction.RemoveDuplicateServers) },
        onDismiss = onDismiss,
    )
    MainDialog.DeleteInvalid -> DeleteConfirmDialog(
        message = stringResource(R.string.confirm_delete_invalid_profiles),
        onConfirm = { onAction(MainAction.RemoveInvalidServers) },
        onDismiss = onDismiss,
    )
    is MainDialog.DeleteOne -> DeleteConfirmDialog(
        message = stringResource(
            if (dialog.row.configType == EConfigType.POLICYGROUP) R.string.confirm_delete_policy_group
            else R.string.confirm_delete_profile
        ),
        targetName = dialog.row.remarks.ifBlank { dialog.row.guid },
        onConfirm = { onAction(MainAction.RemoveServer(dialog.row.guid)) },
        onDismiss = onDismiss,
    )
    is MainDialog.Share -> ShareMethodDialog(
        guid = dialog.row.guid,
        configType = dialog.row.configType,
        includeManagement = dialog.includeManagement,
        onDismiss = onDismiss,
        onAction = onAction,
        onRequestRemove = { onRequestRemove(dialog.row) },
    )
    is MainDialog.QrCode -> QRCodeDialog(bitmap = dialog.bitmap, onDismiss = onDismiss)
}
