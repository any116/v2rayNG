package com.v2ray.ang.ui.subscription

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.v2ray.ang.ui.components.TextEditorState
import com.v2ray.ang.ui.components.restoreTextEditorState

/** Text, selection and flags are one saveable UI draft; loading may seed it only once. */
@Stable
internal class SubEditEditorState(
    val text: TextEditorState = TextEditorState(SubEditForm().textValues()),
    enabled: Boolean = true,
    autoUpdate: Boolean = false,
    allowInsecureUrl: Boolean = false
) {
    var enabled by mutableStateOf(enabled)
    var autoUpdate by mutableStateOf(autoUpdate)
    var allowInsecureUrl by mutableStateOf(allowInsecureUrl)

    operator fun get(field: SubField): TextFieldState = text[field.name]

    fun initialize(form: SubEditForm): Boolean {
        if (!text.initialize(form.textValues())) return false
        enabled = form.enabled
        autoUpdate = form.autoUpdate
        allowInsecureUrl = form.allowInsecureUrl
        return true
    }

    /** Reads the buffers in the click handler, without a Flow collector or recomposition barrier. */
    fun snapshot(): SubEditForm = SubEditForm(
        remarks = text.text(SubField.REMARKS.name),
        url = text.text(SubField.URL.name),
        userAgent = text.text(SubField.USER_AGENT.name),
        requestHeaders = text.text(SubField.REQUEST_HEADERS.name),
        filter = text.text(SubField.FILTER.name),
        enabled = enabled,
        autoUpdate = autoUpdate,
        updateInterval = text.text(SubField.UPDATE_INTERVAL.name),
        allowInsecureUrl = allowInsecureUrl,
        prevProfile = text.text(SubField.PREV_PROFILE.name),
        nextProfile = text.text(SubField.NEXT_PROFILE.name)
    )

    fun error(field: SubField, error: SubEditFieldError?): Int? {
        if (error == null || text.text(field.name) != error.submittedValue) return null
        val flag = when (field) {
            SubField.URL -> allowInsecureUrl
            SubField.UPDATE_INTERVAL -> autoUpdate
            else -> null
        }
        return error.message.takeIf { flag == error.submittedFlag }
    }

    companion object {
        val Saver: Saver<SubEditEditorState, Any> = Saver(
            save = { editor ->
                listOf(
                    checkNotNull(with(TextEditorState.Saver) { save(editor.text) }),
                    editor.enabled,
                    editor.autoUpdate,
                    editor.allowInsecureUrl
                )
            },
            restore = { payload -> restoreEditor(payload) }
        )

        private fun restoreEditor(payload: Any): SubEditEditorState? {
            val values = payload as? List<*> ?: return null
            val defaults = SubEditForm().textValues()
            val text = restoreTextEditorState(values.getOrNull(0), defaults) ?: return null
            return SubEditEditorState(
                text = text,
                enabled = values.getOrNull(1) as? Boolean ?: true,
                autoUpdate = values.getOrNull(2) as? Boolean ?: false,
                allowInsecureUrl = values.getOrNull(3) as? Boolean ?: false
            )
        }
    }
}

@Composable
internal fun rememberSubEditEditorState(documentKey: String): SubEditEditorState =
    key(documentKey) {
        rememberSaveable(saver = SubEditEditorState.Saver) { SubEditEditorState() }
    }
