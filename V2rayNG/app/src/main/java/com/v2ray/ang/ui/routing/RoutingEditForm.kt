package com.v2ray.ang.ui.routing

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.ui.components.TextEditorState
import com.v2ray.ang.ui.components.restoreTextEditorState

@Stable
internal class RoutingEditorState(
    val text: TextEditorState = TextEditorState(RoutingForm().textValues()),
    locked: Boolean = false
) {
    var locked by mutableStateOf(locked)

    operator fun get(field: RoutingField): TextFieldState = text[field.name]

    fun initialize(form: RoutingForm): Boolean {
        if (!text.initialize(form.textValues())) return false
        locked = form.locked
        return true
    }

    fun snapshot(): RoutingForm = RoutingForm(
        remarks = text.text(RoutingField.REMARKS.name),
        locked = locked,
        domain = text.text(RoutingField.DOMAIN.name),
        ip = text.text(RoutingField.IP.name),
        process = text.text(RoutingField.PROCESS.name),
        protocol = text.text(RoutingField.PROTOCOL.name),
        network = text.text(RoutingField.NETWORK.name),
        port = text.text(RoutingField.PORT.name),
        outboundTag = text.text(RoutingField.OUTBOUND.name)
    )

    fun error(field: RoutingField, error: RoutingFieldError?): Int? =
        error?.takeIf { text.text(field.name) == it.submittedValue }?.message

    fun selectProcessAction(): RoutingEditAction.SelectProcess =
        RoutingEditAction.SelectProcess(text.text(RoutingField.PROCESS.name))

    /** The picker edits just this buffer, preserving all other draft text, flags and selections. */
    fun onPickerResult(result: BaseResult) {
        if (result is BaseResult.Selected) {
            this[RoutingField.PROCESS].setTextAndPlaceCursorAtEnd(result.values.joinToString(","))
        }
    }

    companion object {
        val Saver: Saver<RoutingEditorState, Any> = Saver(
            save = { editor ->
                listOf(checkNotNull(with(TextEditorState.Saver) { save(editor.text) }), editor.locked)
            },
            restore = { payload -> restoreEditor(payload) }
        )

        private fun restoreEditor(payload: Any): RoutingEditorState? {
            val values = payload as? List<*> ?: return null
            val text = restoreTextEditorState(
                saved = values.getOrNull(0),
                defaults = RoutingForm().textValues()
            ) ?: return null
            return RoutingEditorState(
                text = text,
                locked = values.getOrNull(1) as? Boolean ?: false
            )
        }
    }
}

@Composable
internal fun rememberRoutingEditorState(documentKey: String): RoutingEditorState =
    key(documentKey) {
        rememberSaveable(saver = RoutingEditorState.Saver) { RoutingEditorState() }
    }
