package com.v2ray.ang.ui.backup

import android.content.res.Configuration
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.WebDavConfig
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.DeleteConfirmDialog
import com.v2ray.ang.ui.components.InputDialog
import com.v2ray.ang.ui.components.InputField
import com.v2ray.ang.ui.components.SelectListDialog
import com.v2ray.ang.ui.components.restoreTextField
import com.v2ray.ang.ui.components.saveTextField

@Immutable
sealed interface BackupDialog {
    data class PickChannel(val restoring: Boolean) : BackupDialog
    data class WebDav(val sessionKey: Long) : BackupDialog
    data object CleanupConfirmation : BackupDialog
}

@Immutable
internal data class WebDavDialogSeed(val sessionKey: Long, val form: WebDavForm)

/** A new key resets the opening seed; the same key lets rememberSaveable restore the draft. */
internal fun webDavDialogSeed(sessionKey: Long, persistedForm: WebDavForm): WebDavDialogSeed =
    WebDavDialogSeed(sessionKey, persistedForm)

internal val WebDavFieldOrder = listOf(
    WebDavField.URL,
    WebDavField.USERNAME,
    WebDavField.PASSWORD,
    WebDavField.PATH
)

/** Passwords are deliberately not restored from Android's instance-state bundle. */
internal val WebDavFieldStatesSaver: Saver<List<TextFieldState>, Any> = Saver(
    save = { states ->
        WebDavFieldOrder.mapIndexed { index, field ->
            if (field == WebDavField.PASSWORD) {
                null
            } else {
                saveTextField(states.getOrNull(index) ?: TextFieldState())
            }
        }
    },
    restore = { payload ->
        val values = payload as? List<*>
        if (values == null) {
            null
        } else {
            WebDavFieldOrder.mapIndexed { index, field ->
                if (field == WebDavField.PASSWORD) {
                    TextFieldState()
                } else {
                    val saved = values.getOrNull(index)
                    if (saved == null) TextFieldState()
                    else restoreTextField(saved) ?: TextFieldState()
                }
            }
        }
    }
)

/** Reads every current field synchronously; normalization preserves password whitespace. */
internal fun snapshotWebDavFields(states: List<TextFieldState>): WebDavConfig {
    return snapshotWebDavValues(states.map { it.text.toString() })
}

internal fun snapshotWebDavValues(values: List<String>): WebDavConfig {
    val fields = WebDavFieldOrder.mapIndexed { index, field ->
        field to values.getOrNull(index).orEmpty()
    }.toMap()
    return WebDavForm(
        baseUrl = fields.getValue(WebDavField.URL),
        username = fields.getValue(WebDavField.USERNAME),
        password = fields.getValue(WebDavField.PASSWORD),
        remotePath = fields.getValue(WebDavField.PATH)
    ).toConfig()
}

/** Dialog visibility is UI-local state; exposing `show`/`dismiss` as fields keeps them stable. */
@Stable
class BackupDialogHost {
    var current by mutableStateOf<BackupDialog?>(null)
        private set
    private var nextWebDavSessionKey by mutableLongStateOf(0L)

    val show: (BackupDialog) -> Unit = { current = it }
    val openWebDav: () -> Unit = {
        nextWebDavSessionKey += 1L
        current = BackupDialog.WebDav(nextWebDavSessionKey)
    }
    val dismiss: () -> Unit = { current = null }

    companion object {
        internal val Saver = Saver<BackupDialogHost, Any>(
            save = {
                val dialog = when (val current = it.current) {
                    null -> listOf("closed")
                    BackupDialog.CleanupConfirmation -> listOf("cleanup")
                    is BackupDialog.PickChannel -> listOf(if (current.restoring) "restore" else "backup")
                    is BackupDialog.WebDav -> listOf("webdav", current.sessionKey)
                }
                listOf(it.nextWebDavSessionKey, dialog)
            },
            restore = { saved ->
                val values = saved as List<*>
                val dialog = values[1] as List<*>
                BackupDialogHost().apply {
                    nextWebDavSessionKey = (values[0] as Number).toLong()
                    current = when (dialog[0] as String) {
                        "webdav" -> BackupDialog.WebDav((dialog[1] as Number).toLong())
                        "cleanup" -> BackupDialog.CleanupConfirmation
                        "restore" -> BackupDialog.PickChannel(restoring = true)
                        "backup" -> BackupDialog.PickChannel(restoring = false)
                        else -> null
                    }
                }
            }
        )
    }
}

/** Restore visibility too, so the dialog's saveable field states can be consumed after rotation. */
@Composable
internal fun rememberBackupDialogHost(): BackupDialogHost =
    rememberSaveable(saver = BackupDialogHost.Saver) { BackupDialogHost() }

@Composable
fun BackupDialogs(
    dialog: BackupDialog?,
    initialForm: WebDavForm,
    onDismiss: () -> Unit,
    onAction: (BackupAction) -> Unit
) = when (dialog) {
    null -> Unit

    is BackupDialog.PickChannel -> ChannelDialog(
        restoring = dialog.restoring,
        onDismiss = onDismiss,
        onAction = onAction
    )

    is BackupDialog.WebDav -> WebDavDialog(
        seed = webDavDialogSeed(dialog.sessionKey, initialForm),
        onDismiss = onDismiss,
        onAction = onAction
    )

    // ConfirmDialog already calls onDismiss after onConfirm.
    BackupDialog.CleanupConfirmation -> DeleteConfirmDialog(
        message = stringResource(R.string.message_profile_storage_cleanup),
        onConfirm = { onAction(BackupAction.CleanupConfirmed) },
        onDismiss = onDismiss
    )
}

@Composable
private fun ChannelDialog(
    restoring: Boolean,
    onDismiss: () -> Unit,
    onAction: (BackupAction) -> Unit
) {
    val onSelected = remember(restoring, onDismiss, onAction) {
        { channel: BackupChannel ->
            onDismiss()
            onAction(BackupAction.ChannelSelected(channel, restoring))
        }
    }
    SelectListDialog(
        options = BackupChannel.entries,
        optionText = { channel ->
            when (channel) {
                BackupChannel.LOCAL -> stringResource(R.string.backup_location_local)
                BackupChannel.WEBDAV -> stringResource(R.string.backup_location_webdav)
            }
        },
        onSelected = onSelected,
        onDismiss = onDismiss,
        title = stringResource(
            if (restoring) {
                R.string.title_configuration_restore
            } else {
                R.string.title_configuration_backup
            }
        )
    )
}

/** Built on the shared [InputDialog]; it already supplies `appFieldColors()` and the spacing. */
@Composable
private fun WebDavDialog(
    seed: WebDavDialogSeed,
    onDismiss: () -> Unit,
    onAction: (BackupAction) -> Unit
) {
    // The form is an opening seed, not a key: persisted-state recomposition cannot erase a draft.
    val states = rememberSaveable(seed.sessionKey, saver = WebDavFieldStatesSaver) {
        WebDavFieldOrder.map { field ->
            TextFieldState(
                when (field) {
                    WebDavField.URL -> seed.form.baseUrl
                    WebDavField.USERNAME -> seed.form.username
                    WebDavField.PASSWORD -> seed.form.password
                    WebDavField.PATH -> seed.form.remotePath
                }
            )
        }
    }
    val urlLabel = stringResource(R.string.title_webdav_url)
    val userLabel = stringResource(R.string.title_webdav_user)
    val passLabel = stringResource(R.string.title_webdav_pass)
    val pathLabel = stringResource(R.string.title_webdav_remote_path)

    val fields = remember(states, urlLabel, userLabel, passLabel, pathLabel) {
        WebDavFieldOrder.zip(states).map { (field, state) ->
            when (field) {
                WebDavField.URL -> InputField(
                    label = urlLabel,
                    state = state,
                    keyboardType = KeyboardType.Uri
                )
                WebDavField.USERNAME -> InputField(
                    label = userLabel,
                    state = state
                )
                WebDavField.PASSWORD -> InputField(
                    label = passLabel,
                    state = state,
                    keyboardType = KeyboardType.Password,
                    isPassword = true
                )
                WebDavField.PATH -> InputField(
                    label = pathLabel,
                    state = state
                )
            }
        }
    }
    InputDialog(
        title = stringResource(R.string.title_webdav_config_setting),
        fields = fields,
        confirmText = stringResource(R.string.menu_item_save_config),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { values -> onAction(BackupAction.SaveWebDav(snapshotWebDavValues(values))) },
        onDismiss = onDismiss
    )
}

@Preview(showBackground = true)
@Composable
private fun WebDavDialogPreview() = AppTheme {
    WebDavDialog(
        seed = webDavDialogSeed(
            sessionKey = 1L,
            persistedForm = WebDavForm(
                baseUrl = "https://dav.example.com/remote.php/dav",
                username = "alice",
                password = "secret",
                remotePath = "/v2rayNG"
            )
        ),
        onDismiss = {},
        onAction = {}
    )
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChannelDialogPreview() = AppTheme {
    ChannelDialog(restoring = true, onDismiss = {}, onAction = {})
}
