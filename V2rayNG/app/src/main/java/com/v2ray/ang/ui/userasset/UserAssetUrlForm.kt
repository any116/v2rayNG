package com.v2ray.ang.ui.userasset

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.v2ray.ang.ui.components.TextEditorState
import com.v2ray.ang.ui.components.restoreTextEditorState

@Stable
internal class AssetEditorState(val text: TextEditorState = TextEditorState(AssetForm().textValues())) {
    operator fun get(field: AssetField): TextFieldState = text[field.name]

    fun initialize(form: AssetForm): Boolean = text.initialize(form.textValues())

    fun snapshot(): AssetForm = AssetForm(
        remarks = text.text(AssetField.REMARKS.name),
        url = text.text(AssetField.URL.name)
    )

    fun error(field: AssetField, error: AssetFieldError?): Int? =
        error?.takeIf { text.text(field.name) == it.submittedValue }?.message

    companion object {
        val Saver: Saver<AssetEditorState, Any> = Saver(
            save = { editor -> with(TextEditorState.Saver) { save(editor.text) } },
            restore = { payload -> restoreEditor(payload) }
        )

        private fun restoreEditor(payload: Any): AssetEditorState? =
            restoreTextEditorState(payload, AssetForm().textValues())?.let(::AssetEditorState)
    }
}

@Composable
internal fun rememberAssetEditorState(documentKey: String): AssetEditorState =
    key(documentKey) {
        rememberSaveable(saver = AssetEditorState.Saver) { AssetEditorState() }
    }
