package com.v2ray.ang.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

private val DialogGap = 8.dp
private val OptionVerticalPad = 12.dp
private val OptionHorizontalPad = 4.dp
private val ConfirmIconSize = 18.dp
private const val OptionContentType = "select-option"

/** Delegate each field's text and directed selection to the foundation saver. */
internal val InputFieldStatesSaver: Saver<List<TextFieldState>, Any> = TextFieldStateListSaver

@Composable
fun ConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    confirmText: String = stringResource(R.string.action_ok),
    dismissText: String? = stringResource(R.string.action_cancel),
    confirmIcon: @Composable (() -> Unit)? = null,
    confirmContentDescription: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = {
            Text(
                text = message,
                modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(); onDismiss() },
                modifier = Modifier.semantics {
                    if (confirmContentDescription != null) contentDescription = confirmContentDescription
                }
            ) {
                if (confirmIcon != null) {
                    confirmIcon()
                    Spacer(Modifier.width(DialogGap))
                }
                Text(confirmText)
            }
        },
        dismissButton = dismissText?.let { text ->
            { TextButton(onClick = onDismiss) { Text(text) } }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/** Keeps the target readable in the dialog and on its confirmation button, even with large text. */
@Composable
fun DeleteConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    targetName: String? = null
) {
    val target = targetName?.takeIf { it.isNotBlank() }
    val deleteText = stringResource(R.string.action_delete)
    ConfirmDialog(
        message = if (target == null) message else "$target\n\n$message",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        title = deleteText,
        confirmText = deleteText,
        confirmContentDescription = target?.let {
            stringResource(R.string.acc_action_target, deleteText, it)
        },
        confirmIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_delete_24dp),
                contentDescription = null,
                modifier = Modifier.size(ConfirmIconSize)
            )
        }
    )
}

@Stable
data class InputField(
    val label: String,
    val state: TextFieldState,
    val keyboardType: KeyboardType = KeyboardType.Text,
    /** Use Material3's secure state-based field. Secure fields are always single-line. */
    val isPassword: Boolean = false
)

/** A confirmation snapshot is independent of collectors, recomposition and subsequent edits. */
internal fun snapshotInputFields(fields: List<InputField>): List<String> =
    fields.map { it.state.text.toString() }

/**
 * Renders caller-owned fields and reads them synchronously on confirmation.
 * The caller owns saveable state and decides when a new dialog seeds committed values.
 */
@Composable
fun InputDialog(
    title: String,
    fields: List<InputField>,
    confirmText: String,
    dismissText: String,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(DialogGap)
            ) {
                fields.forEach { field ->
                    key(field.state) {
                        InputDialogField(field)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(snapshotInputFields(fields)) }) {
                Text(confirmText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun InputDialogField(field: InputField) {
    val keyboardOptions = KeyboardOptions(keyboardType = field.keyboardType)
    val modifier = Modifier.fillMaxWidth()
    if (field.isPassword) {
        var passwordVisible by remember { mutableStateOf(false) }
        val visibilityAction = stringResource(
            if (passwordVisible) R.string.acc_hide_password else R.string.acc_show_password
        )
        OutlinedSecureTextField(
            state = field.state,
            label = { Text(field.label) },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        painter = painterResource(
                            if (passwordVisible) {
                                R.drawable.ic_visibility_off_24dp
                            } else {
                                R.drawable.ic_visibility_24dp
                            }
                        ),
                        contentDescription = visibilityAction
                    )
                }
            },
            textObfuscationMode = if (passwordVisible) {
                TextObfuscationMode.Visible
            } else {
                TextObfuscationMode.Hidden
            },
            keyboardOptions = keyboardOptions,
            colors = appFieldColors(),
            modifier = modifier
        )
    } else {
        OutlinedTextField(
            state = field.state,
            label = { Text(field.label) },
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = keyboardOptions,
            colors = appFieldColors(),
            modifier = modifier
        )
    }
}

@Composable
fun QRCodeDialog(bitmap: Bitmap?, onDismiss: () -> Unit) {
    if (bitmap == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.acc_qr_code),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun <T> SelectListDialog(
    options: List<T>,
    optionText: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    selectedOption: T? = null,
    showRadio: Boolean = false,
    optionKey: (T) -> Any = { it as Any }
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = {
            LazyColumn {
                items(
                    items = options,
                    key = { optionKey(it) },
                    contentType = { OptionContentType }
                ) { option ->
                    val isSelected = option == selectedOption
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (showRadio) {
                                    Modifier.selectable(
                                        selected = isSelected,
                                        onClick = { onSelected(option) },
                                        role = Role.RadioButton
                                    )
                                } else {
                                    Modifier.clickable { onSelected(option) }
                                }
                            )
                            .padding(
                                vertical = OptionVerticalPad,
                                horizontal = OptionHorizontalPad
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showRadio) {
                            RadioButton(selected = isSelected, onClick = null)
                            Spacer(modifier = Modifier.width(DialogGap))
                        }
                        Text(
                            text = optionText(option),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

// ===== previews =====

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConfirmDialogPreview() = AppTheme {
    ConfirmDialog(
        title = "Remove",
        message = "A confirmation body long enough to wrap onto a second and even a third line.",
        onConfirm = {},
        onDismiss = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun InputDialogPreview() = AppTheme {
    InputDialog(
        title = "Socks port",
        fields = listOf(
            InputField(
                label = "Port",
                state = rememberTextFieldState("10808"),
                keyboardType = KeyboardType.Number
            )
        ),
        confirmText = "OK",
        dismissText = "Cancel",
        onConfirm = {},
        onDismiss = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun SelectListDialogPreview() = AppTheme {
    SelectListDialog(
        title = "Log level",
        options = listOf("warning", "debug", "info"),
        optionText = { it },
        selectedOption = "warning",
        showRadio = true,
        onSelected = {},
        onDismiss = {}
    )
}
