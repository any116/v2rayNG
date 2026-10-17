package com.v2ray.ang.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** Covers the dialog's saver payload without a Composition, Activity or IME. */
class InputFieldStateSaverTest {

    @Test
    fun confirmationSnapshotsTheLastEditWithoutACollector() {
        val address = TextFieldState("committed address")
        val password = TextFieldState("committed password")
        val fields = listOf(
            InputField(label = "Address", state = address),
            InputField(label = "Password", state = password, isPassword = true)
        )

        address.setTextAndPlaceCursorAtEnd("final address")
        password.setTextAndPlaceCursorAtEnd("final password")
        val snapshot = snapshotInputFields(fields)
        address.setTextAndPlaceCursorAtEnd("later edit")
        password.clearText()

        assertEquals(listOf("final address", "final password"), snapshot)
        assertEquals("later edit", address.text.toString())
    }

    @Test
    fun multipleFieldsKeepTheirTextAndIndependentSelections() {
        val states = listOf(
            TextFieldState("https://example.com/dav", TextRange(8, 19)),
            TextFieldState("user", TextRange(0)),
            TextFieldState("password", TextRange(6, 2))
        )

        val restored = restore(save(states))

        assertEquals(states.size, restored.size)
        states.zip(restored).forEach { (original, result) ->
            assertNotSame(original, result)
            assertEquals(original.text.toString(), result.text.toString())
            assertEquals(original.selection.start, result.selection.start)
            assertEquals(original.selection.end, result.selection.end)
        }
    }

    @Test
    fun multilineReverseSelectionKeepsAnchorAndFocus() {
        val text = "first\n第二行\r\nlast"
        val selection = TextRange(text.length - 1, 2)

        val restored = restore(save(listOf(TextFieldState(text, selection)))).single()

        assertEquals(text, restored.text.toString())
        assertEquals(selection.start, restored.selection.start)
        assertEquals(selection.end, restored.selection.end)
    }

    @Test
    fun emptyTextAndCursorBoundariesRoundTrip() {
        val restored = restore(
            save(
                listOf(
                    TextFieldState("", TextRange.Zero),
                    TextFieldState("start", TextRange.Zero),
                    TextFieldState("end", TextRange(3))
                )
            )
        )

        assertEquals(listOf("", "start", "end"), restored.map { it.text.toString() })
        assertEquals(listOf(TextRange.Zero, TextRange.Zero, TextRange(3)), restored.map { it.selection })
    }

    @Test
    fun selectionOnlyEditsAreSavedAsAnIndependentSnapshot() {
        val state = TextFieldState("first\nsecond")
        state.edit { selection = TextRange(10, 1) }

        val saved = save(listOf(state))
        state.edit { selection = TextRange.Zero }
        val restored = restore(saved).single()

        assertEquals("first\nsecond", restored.text.toString())
        assertEquals(TextRange(10, 1), restored.selection)
        assertEquals(TextRange.Zero, state.selection)
    }

    @Test
    fun textEditsAndReverseSelectionAreSavedAsAnIndependentSnapshot() {
        val state = TextFieldState("committed value")
        val draftText = "first\n第二行\r\nlast"
        val draftSelection = TextRange(draftText.length - 1, 2)
        state.edit {
            replace(0, length, draftText)
            selection = draftSelection
        }

        val saved = save(listOf(state))
        state.edit {
            replace(0, length, "changed again")
            selection = TextRange.Zero
        }
        val restored = restore(saved).single()

        assertNotSame(state, restored)
        assertEquals(draftText, restored.text.toString())
        assertEquals(draftSelection.start, restored.selection.start)
        assertEquals(draftSelection.end, restored.selection.end)
        assertEquals("changed again", state.text.toString())
        assertEquals(TextRange.Zero, state.selection)
    }

    @Test
    fun multipleEditedFieldsRestoreInOrderFromSerializablePayload() {
        val draftTexts = listOf("https://例子.test/dav", "alice", "new password", "/backup/第二行")
        val draftSelections = listOf(TextRange(8, 15), TextRange(1), TextRange(10, 2), TextRange(11))
        val states = List(draftTexts.size) { TextFieldState("committed-$it") }
        states.forEachIndexed { index, state ->
            state.edit {
                replace(0, length, draftTexts[index])
                selection = draftSelections[index]
            }
        }

        // Bundle accepts Serializable lists. A real serialization boundary also rejects any
        // accidentally retained TextFieldState or other non-saveable nested object.
        val restored = restore(serializedCopy(save(states)))

        assertEquals(draftTexts, restored.map { it.text.toString() })
        assertEquals(draftSelections, restored.map { it.selection })
        states.zip(restored).forEach { (original, result) -> assertNotSame(original, result) }
    }

    @Test
    fun malformedNestedPayloadDoesNotThrowOrCreatePartialFields() {
        assertNull(InputFieldStatesSaver.restore(listOf(listOf("not-a-text-field"))))
    }

    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = isSaveablePayload(value)
    }

    private fun save(states: List<TextFieldState>): Any {
        val saved = checkNotNull(with(InputFieldStatesSaver) { saverScope.save(states) })
        assertTrue(isSaveablePayload(saved), "The complete saver payload must be Bundle-compatible")
        return saved
    }

    private fun restore(saved: Any): List<TextFieldState> =
        checkNotNull(InputFieldStatesSaver.restore(saved))

    private fun serializedCopy(value: Any): Any {
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
            output.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }

    /** The Bundle-compatible subset used by the foundation saver's nested payload. */
    private fun isSaveablePayload(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Char -> true
        is Byte, is Short, is Int, is Long, is Float, is Double -> true
        is List<*> -> value is Serializable && value.all(::isSaveablePayload)
        else -> false
    }
}
