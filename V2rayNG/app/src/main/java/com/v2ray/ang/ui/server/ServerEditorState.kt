package com.v2ray.ang.ui.server

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import com.v2ray.ang.ui.components.restoreTextField
import com.v2ray.ang.ui.components.saveTextField
import java.util.UUID

/** The only mutable editor draft. No String mirror or per-keystroke ViewModel action is needed. */
@Stable
internal class ServerEditorState private constructor(
    private val fields: Map<ServerField, TextFieldState>,
    val rawContent: TextFieldState
) {
    constructor() : this(ServerField.entries.associateWith { TextFieldState() }, TextFieldState())

    var allowInsecure by mutableStateOf(false)
    var groupTestOutbounds by mutableStateOf(true)
    var masqueWarpEnabled by mutableStateOf(false)
    private val members = mutableStateListOf<ServerChainMemberState>()
    val chainMembers: List<ServerChainMemberState> get() = members
    var isInitialized by mutableStateOf(false)
        private set

    operator fun get(field: ServerField): TextFieldState = checkNotNull(fields[field]) {
        "Unknown server form field '$field'"
    }

    fun text(field: ServerField): String = this[field].text.toString()

    fun toggleMasqueWarp(enabled: Boolean) {
        masqueWarpEnabled = enabled
        if (enabled) {
            this[ServerField.USERNAME].edit { replace(0, length, "") }
            this[ServerField.PASSWORD].edit { replace(0, length, "") }
        }
    }

    fun visibleErrors(errors: Map<ServerField, ServerFieldError>): Map<ServerField, Int> = buildMap {
        errors.forEach { (field, error) ->
            if (text(field) == error.submittedValue) put(field, error.message)
        }
    }

    /** A restored draft wins even when database metadata finishes loading later. */
    fun initialize(form: ServerForm, raw: String): Boolean {
        if (isInitialized) return false
        ServerField.entries.forEach { field ->
            this[field].edit {
                replace(0, length, field.get(form))
                selection = TextRange.Zero
            }
        }
        rawContent.edit {
            replace(0, length, raw)
            selection = TextRange.Zero
        }
        allowInsecure = form.allowInsecure
        groupTestOutbounds = form.groupTestOutbounds
        masqueWarpEnabled = form.masqueWarpEnabled
        members.addAll(form.chainMembers.map { ServerChainMemberState(it.id, TextFieldState(it.remarks)) })
        isInitialized = true
        return true
    }

    /**
     * Synchronous main-thread boundary: includes edits that have not caused any recomposition.
     * Callers must check [isInitialized] before invoking this function.
     */
    fun snapshot(): ServerEditorSnapshot {
        check(isInitialized) { "Cannot save an editor before its initial values are loaded" }
        val form = ServerField.entries.fold(
            ServerForm(
                allowInsecure = allowInsecure,
                groupTestOutbounds = groupTestOutbounds,
                masqueWarpEnabled = masqueWarpEnabled,
                chainMembers = members.map { ChainMember(it.id, it.remarks.text.toString()) }
            )
        ) { form, field -> field.set(form, text(field)) }
        return ServerEditorSnapshot(form, rawContent.text.toString())
    }

    fun addChainMember() {
        if (isInitialized) members.add(ServerChainMemberState(UUID.randomUUID().toString(), TextFieldState()))
    }

    fun chainMemberNeedsConfirmation(id: String): Boolean =
        members.firstOrNull { it.id == id }?.remarks?.text?.isNotBlank() == true

    fun removeChainMember(id: String) {
        members.removeAll { it.id == id }
    }

    fun moveChainMember(fromId: String, toId: String) {
        val from = members.indexOfFirst { it.id == fromId }
        val to = members.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return
        members.add(to, members.removeAt(from))
    }

    /** A certificate response is an explicit single-field edit, never an old-form replacement. */
    fun applyCertificate(pinnedCA256: String) {
        if (isInitialized) this[ServerField.PINNED_CA256].setTextAndPlaceCursorAtEnd(pinnedCA256)
    }

    companion object {
        /** Foundation owns text/selection serialization, including reversed selections. */
        val Saver: Saver<ServerEditorState, Any> = Saver(
            save = { editor ->
                if (!editor.isInitialized) null
                else listOf(
                    ServerField.entries.map { listOf(it.name, saveTextField(editor[it])) },
                    saveTextField(editor.rawContent),
                    editor.allowInsecure,
                    editor.groupTestOutbounds,
                    editor.members.map { listOf(it.id, saveTextField(it.remarks)) },
                    editor.masqueWarpEnabled,
                )
            },
            restore = { saved -> restoreEditor(saved) }
        )

        private fun restoreEditor(saved: Any): ServerEditorState? {
            val values = saved as? List<*> ?: return null
            val entries = values.getOrNull(0) as? List<*> ?: return null
            val restored = linkedMapOf<ServerField, TextFieldState>()
            for (entry in entries) {
                val pair = entry as? List<*> ?: return null
                val name = pair.getOrNull(0) as? String ?: return null
                val field = runCatching { ServerField.valueOf(name) }.getOrNull() ?: continue
                val fieldState = restoreTextField(pair.getOrNull(1)) ?: return null
                restored[field] = fieldState
            }
            if (ServerField.entries.any { it !in restored }) return null

            val rawContent = restoreTextField(values.getOrNull(1)) ?: return null
            val fields = ServerField.entries.associateWith { restored.getValue(it) }
            val membersPayload = values.getOrNull(4) as? List<*> ?: return null
            val members = mutableListOf<ServerChainMemberState>()
            for (entry in membersPayload) {
                val pair = entry as? List<*> ?: return null
                val id = pair.getOrNull(0) as? String ?: return null
                val remarks = restoreTextField(pair.getOrNull(1)) ?: return null
                members += ServerChainMemberState(id, remarks)
            }

            return ServerEditorState(fields, rawContent).apply {
                allowInsecure = values.getOrNull(2) as? Boolean ?: false
                groupTestOutbounds = values.getOrNull(3) as? Boolean ?: true
                masqueWarpEnabled = values.getOrNull(5) as? Boolean ?: false
                this.members.addAll(members)
                isInitialized = true
            }
        }
    }
}

@Stable
internal class ServerChainMemberState(val id: String, val remarks: TextFieldState)

@Composable
internal fun rememberServerEditorState(documentKey: String): ServerEditorState = key(documentKey) {
    rememberSaveable(saver = ServerEditorState.Saver) { ServerEditorState() }
}
