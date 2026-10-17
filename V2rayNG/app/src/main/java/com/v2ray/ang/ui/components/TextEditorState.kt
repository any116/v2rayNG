package com.v2ray.ang.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * UI-owned form buffers. Loaded values seed them once; no String mirror is updated during editing.
 * Feature holders add their own typed flags and expose immutable snapshots at command boundaries.
 * All access is on the UI thread. Persisting before load retains the uninitialized marker, so an
 * empty/default snapshot cannot suppress the first real load after process recreation.
 */
@Stable
internal class TextEditorState private constructor(
    private val fields: MutableMap<String, TextFieldState>,
    initialized: Boolean
) {
    constructor(initialValues: Map<String, String> = emptyMap()) : this(
        initialValues.mapValuesTo(linkedMapOf()) { TextFieldState(it.value) },
        false
    )

    var isInitialized by mutableStateOf(initialized)
        private set

    operator fun get(key: String): TextFieldState = fields.getOrPut(key) { TextFieldState() }

    fun text(key: String): String = this[key].text.toString()

    /** Restored buffers are already initialized and always win over a later database load. */
    fun initialize(values: Map<String, String>): Boolean {
        if (isInitialized) return false
        values.forEach { (key, text) ->
            val field = fields.getOrPut(key) { TextFieldState(text) }
            if (!field.text.contentEquals(text)) field.setTextAndPlaceCursorAtEnd(text)
        }
        fields.keys.retainAll(values.keys)
        isInitialized = true
        return true
    }

    /**
     * Aligns a restored buffer with the current feature schema. Saver payloads can outlive a
     * release, so newly added fields must be created and removed fields must no longer be exposed.
     */
    fun alignTo(keys: Set<String>) {
        fields.keys.retainAll(keys)
        keys.forEach { key -> fields.getOrPut(key) { TextFieldState() } }
    }

    /**
     * Reconciles a restored payload with the current schema.
     *
     * The return value is false when a current field was absent from the payload. Callers should
     * discard that incomplete initialized draft and let the real form initialize a fresh editor;
     * filling the missing field with an empty value would otherwise suppress the database load.
     */
    fun reconcileKeys(defaults: Map<String, String>): Boolean {
        val complete = defaults.keys.all(fields::containsKey)
        defaults.forEach { (key, text) -> fields.getOrPut(key) { TextFieldState(text) } }
        fields.keys.retainAll(defaults.keys)
        return complete
    }

    /** An independent snapshot, read synchronously by the handler that dispatches Save. */
    fun snapshot(): Map<String, String> = fields.mapValues { it.value.text.toString() }

    companion object {
        val Saver: Saver<TextEditorState, Any> = Saver(
            save = { editor ->
                listOf(
                    editor.isInitialized,
                    editor.fields.map { (key, field) ->
                        listOf(key, saveTextField(field))
                    }
                )
            },
            restore = { payload ->
                val saved = payload as? List<*>
                if (saved == null) {
                    null
                } else {
                    val initialized = saved.getOrNull(0) as? Boolean ?: false
                    val fields = linkedMapOf<String, TextFieldState>()
                    (saved.getOrNull(1) as? List<*>)?.forEach { entry ->
                        val pair = entry as? List<*> ?: return@forEach
                        val key = pair.getOrNull(0) as? String ?: return@forEach
                        val fieldPayload = pair.getOrNull(1) ?: return@forEach
                        val field = restoreTextField(fieldPayload) ?: return@forEach
                        fields[key] = field
                    }
                    TextEditorState(fields, initialized)
                }
            }
        )
    }
}

/** Restores a feature text buffer and rejects an incomplete schema without throwing. */
internal fun restoreTextEditorState(
    saved: Any?,
    defaults: Map<String, String>
): TextEditorState? {
    val restored = saved?.let { TextEditorState.Saver.restore(it) } ?: return null
    return if (restored.reconcileKeys(defaults)) restored else TextEditorState(defaults)
}
