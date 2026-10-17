package com.v2ray.ang.ui.server

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class ServerEditorStateTest {

    @Test
    fun uninitializedEditorCannotSnapshotOrPersistAnEmptyForm() {
        val editor = ServerEditorState()

        assertFalse(editor.isInitialized)
        assertThrows(IllegalStateException::class.java) { editor.snapshot() }
        assertNull(with(ServerEditorState.Saver) { saverScope.save(editor) })
    }

    @Test
    fun initializationPreservesEveryFieldAndDefault() {
        val form = ServerField.entries.fold(ServerForm()) { form, field ->
            field.set(form, "initial-${field.name}")
        }.copy(allowInsecure = true, groupTestOutbounds = false)
        val editor = ServerEditorState()

        assertTrue(editor.initialize(form, "loaded raw"))
        assertEquals(ServerEditorSnapshot(form, "loaded raw"), editor.snapshot())
        assertEquals(TextRange.Zero, editor.rawContent.selection)

        val defaults = ServerForm()
        val defaultEditor = ServerEditorState()
        defaultEditor.initialize(defaults, "")
        assertEquals(defaults, defaultEditor.snapshot().form)
    }

    @Test
    fun snapshotImmediatelyReadsAllProductionFieldsWithoutACollectorOrRecomposition() {
        val editor = initializedEditor()
        ServerField.entries.forEach { field ->
            editor[field].setTextAndPlaceCursorAtEnd("latest-${field.name}")
        }
        editor.allowInsecure = true
        editor.groupTestOutbounds = false
        editor.rawContent.setTextAndPlaceCursorAtEnd("{\n  \"latest\": true\n}")

        val snapshot = editor.snapshot()

        ServerField.entries.forEach { field ->
            assertEquals("latest-${field.name}", field.get(snapshot.form), field.name)
        }
        // Also catches a ServerForm String property omitted from ServerField entirely.
        val strings = ServerForm::class.java.declaredFields.filter { it.type == String::class.java }
        assertEquals(ServerField.entries.size, strings.size)
        assertEquals(
            ServerField.entries.map { "latest-${it.name}" }.toSet(),
            strings.map {
                it.isAccessible = true
                it.get(snapshot.form)
            }.toSet()
        )
        assertTrue(snapshot.form.allowInsecure)
        assertFalse(snapshot.form.groupTestOutbounds)
        assertEquals("{\n  \"latest\": true\n}", snapshot.rawContent)
    }

    @Test
    fun saveSnapshotIsIsolatedFromLaterTextFlagsAndChainEdits() {
        val editor = initializedEditor()
        editor[ServerField.REMARKS].setTextAndPlaceCursorAtEnd("save this")
        editor.rawContent.setTextAndPlaceCursorAtEnd("save raw")
        val member = editor.chainMembers.first()
        member.remarks.setTextAndPlaceCursorAtEnd("save member")
        val snapshot = editor.snapshot()
        val action = ServerAction.Save(snapshot.form, snapshot.rawContent)

        editor[ServerField.REMARKS].setTextAndPlaceCursorAtEnd("later")
        editor.rawContent.setTextAndPlaceCursorAtEnd("later raw")
        editor.allowInsecure = true
        member.remarks.setTextAndPlaceCursorAtEnd("later member")
        editor.removeChainMember(member.id)
        editor.addChainMember()

        assertEquals("save this", action.form.remarks)
        assertEquals("save raw", action.rawContent)
        assertFalse(action.form.allowInsecure)
        assertEquals("save member", action.form.chainMembers.first().remarks)
        assertEquals(member.id, action.form.chainMembers.first().id)
    }

    @Test
    fun laterLoadCannotReplaceAnInitializedDraftOrItsSelection() {
        val editor = initializedEditor()
        editor[ServerField.ADDRESS].edit {
            replace(0, length, "draft address")
            selection = TextRange(10, 2)
        }
        val before = editor.snapshot()

        assertFalse(editor.initialize(ServerForm(address = "late load"), "late raw"))

        assertEquals(before, editor.snapshot())
        assertEquals(TextRange(10, 2), editor[ServerField.ADDRESS].selection)
    }

    @Test
    fun clearingRawAndTextFieldsIsCapturedImmediatelyAndRestoresAsAnEditedDraft() {
        val editor = ServerEditorState()
        editor.initialize(ServerForm(remarks = "stored remarks"), "stored raw")
        editor[ServerField.REMARKS].setTextAndPlaceCursorAtEnd("")
        editor.rawContent.setTextAndPlaceCursorAtEnd("")
        editor.chainMembers.map { it.id }.forEach(editor::removeChainMember)
        val saved = checkNotNull(with(ServerEditorState.Saver) { saverScope.save(editor) })

        val restored = checkNotNull(ServerEditorState.Saver.restore(serializedCopy(saved)))

        assertFalse(restored.initialize(ServerForm(remarks = "late remarks"), "late raw"))
        assertEquals("", restored.snapshot().form.remarks)
        assertEquals("", restored.snapshot().rawContent)
        assertTrue(restored.chainMembers.isEmpty())
    }

    @Test
    fun chainReorderKeepsFieldIdentityAndRemovalUsesCurrentDraft() {
        val editor = ServerEditorState()
        editor.initialize(
            ServerForm(chainMembers = listOf(ChainMember("a", "first"), ChainMember("b", "second"))),
            ""
        )
        val first = editor.chainMembers.first()
        first.remarks.edit { selection = TextRange(4, 1) }
        editor.moveChainMember("a", "b")
        editor.moveChainMember("missing", "b")

        assertEquals(listOf("b", "a"), editor.snapshot().form.chainMembers.map { it.id })
        assertSame(first, editor.chainMembers.last())
        assertEquals(TextRange(4, 1), first.remarks.selection)
        assertTrue(editor.chainMemberNeedsConfirmation("a"))
        first.remarks.setTextAndPlaceCursorAtEnd("  ")
        assertFalse(editor.chainMemberNeedsConfirmation("a"))
        editor.removeChainMember("a")
        editor.removeChainMember("missing")
        editor.addChainMember()

        assertEquals("b", editor.chainMembers.first().id)
        assertEquals(2, editor.chainMembers.map { it.id }.toSet().size)
        assertEquals("", editor.chainMembers.last().remarks.text.toString())
    }

    @Test
    fun certificateResultOnlyUpdatesPinnedFieldAfterRequestSnapshot() {
        val editor = initializedEditor()
        val request = editor.snapshot().form
        editor[ServerField.ADDRESS].edit {
            replace(0, length, "edited during request")
            selection = TextRange(15, 2)
        }
        editor[ServerField.PASSWORD].setTextAndPlaceCursorAtEnd("new password")
        editor.rawContent.setTextAndPlaceCursorAtEnd("new raw")
        editor.allowInsecure = true
        editor.addChainMember()
        val before = editor.snapshot()
        val addressField = editor[ServerField.ADDRESS]

        editor.applyCertificate("certificate result")

        assertEquals(before.form.copy(pinnedCA256 = "certificate result"), editor.snapshot().form)
        assertEquals(before.rawContent, editor.snapshot().rawContent)
        assertSame(addressField, editor[ServerField.ADDRESS])
        assertEquals(TextRange(15, 2), addressField.selection)
        assertEquals("", request.pinnedCA256)
    }

    @Test
    fun submittedValidationErrorsDoNotMarkANewerDraftValueAsInvalid() {
        val editor = initializedEditor()
        val submitted = editor.snapshot().form
        val errors = submitted.validationErrors(mapOf(ServerField.ADDRESS to 123))
        editor[ServerField.PASSWORD].setTextAndPlaceCursorAtEnd("unrelated edit")
        assertEquals(mapOf(ServerField.ADDRESS to 123), editor.visibleErrors(errors))

        editor[ServerField.ADDRESS].setTextAndPlaceCursorAtEnd("corrected address")

        assertTrue(editor.visibleErrors(errors).isEmpty())
        assertEquals(submitted.address, errors.getValue(ServerField.ADDRESS).submittedValue)
    }

    @Test
    fun serializableSaverRestoresAllFieldsDirectionalSelectionsFlagsAndChainOrderBeforeLateLoad() {
        val editor = initializedEditor()
        ServerField.entries.forEachIndexed { index, field ->
            editor[field].edit {
                replace(0, length, "draft-${field.name}")
                selection = if (index % 2 == 0) TextRange(2, 6) else TextRange(6, 2)
            }
        }
        editor.rawContent.edit {
            replace(0, length, "first\n第二行\nlast")
            selection = TextRange(11, 2)
        }
        editor.allowInsecure = true
        editor.groupTestOutbounds = false
        editor.addChainMember()
        val addedId = editor.chainMembers.last().id
        editor.chainMembers.last().remarks.setTextAndPlaceCursorAtEnd("new member")
        val member = editor.chainMembers.first()
        member.remarks.edit {
            replace(0, length, "chain draft")
            selection = TextRange(9, 1)
        }
        editor.moveChainMember(member.id, editor.chainMembers.last().id)
        val expected = editor.snapshot()
        val saved = checkNotNull(with(ServerEditorState.Saver) { saverScope.save(editor) })
        editor[ServerField.ADDRESS].setTextAndPlaceCursorAtEnd("after save")
        editor.removeChainMember(member.id)

        val restored = checkNotNull(ServerEditorState.Saver.restore(serializedCopy(saved)))

        assertTrue(restored.isInitialized)
        assertFalse(restored.initialize(ServerForm(), "late load"))
        assertEquals(expected, restored.snapshot())
        ServerField.entries.forEachIndexed { index, field ->
            assertNotSame(editor[field], restored[field])
            assertEquals(
                if (index % 2 == 0) TextRange(2, 6) else TextRange(6, 2),
                restored[field].selection
            )
        }
        assertEquals(TextRange(11, 2), restored.rawContent.selection)
        assertEquals(TextRange(9, 1), restored.chainMembers.last().remarks.selection)
        assertEquals(addedId, restored.chainMembers[1].id)
        assertEquals("new member", restored.chainMembers[1].remarks.text.toString())
    }

    @Test
    fun saverRejectsMissingCurrentFieldInsteadOfRestoringAnInitializedEmptyBuffer() {
        val editor = initializedEditor()
        val saved = checkNotNull(with(ServerEditorState.Saver) { saverScope.save(editor) }) as List<*>
        val fieldsWithoutTheFirst = (saved[0] as List<*>).drop(1)
        val payload = listOf(fieldsWithoutTheFirst, saved[1], saved[2], saved[3], saved[4])

        assertNull(ServerEditorState.Saver.restore(payload))
    }

    @Test
    fun saverSkipsUnknownRemovedEnumFieldsWhileKeepingCurrentFields() {
        val editor = initializedEditor()
        val saved = checkNotNull(with(ServerEditorState.Saver) { saverScope.save(editor) }) as List<*>
        val fieldsWithRemovedEntry = (saved[0] as List<*>) + listOf(listOf("REMOVED_FIELD"))
        val payload = listOf(fieldsWithRemovedEntry, saved[1], saved[2], saved[3], saved[4])

        assertNotNull(ServerEditorState.Saver.restore(payload))
    }

    private fun initializedEditor() = ServerEditorState().apply { initialize(ServerForm(), "") }

    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun serializedCopy(value: Any): Any {
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
            output.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }
}
