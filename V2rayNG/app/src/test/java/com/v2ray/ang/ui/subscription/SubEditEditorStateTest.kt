package com.v2ray.ang.ui.subscription

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import com.v2ray.ang.data.entities.SubscriptionItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SubEditEditorStateTest {
    private val completeForm = SubEditForm(
        remarks = "subscription",
        url = "https://example.com/sub",
        userAgent = "agent",
        requestHeaders = "X-Test: value",
        filter = "include.*",
        enabled = false,
        autoUpdate = true,
        updateInterval = "90",
        allowInsecureUrl = true,
        prevProfile = "entry",
        nextProfile = "exit"
    )

    @Test
    fun allFieldsMapFromEntityThroughEditorAndBack() {
        val source = SubscriptionItem(
            guid = "sub",
            addedTime = 123L,
            remarks = "subscription",
            url = "https://example.com/sub",
            userAgent = "agent",
            requestHeaders = "X-Test: value",
            filter = "include.*",
            enabled = false,
            autoUpdate = true,
            updateInterval = 90L,
            allowInsecureUrl = true,
            prevProfile = "entry",
            nextProfile = "exit"
        )
        val editor = SubEditEditorState()
        assertTrue(editor.initialize(source.toSubEditForm()))

        assertEquals(completeForm, editor.snapshot())
        assertEquals(SubField.entries.map { it.name }.toSet(), completeForm.textValues().keys)
        assertEquals(source, source.copy().applySubEditForm(editor.snapshot()))
    }

    @Test
    fun immediateSnapshotReadsEveryBufferWithoutACollectorAndDoesNotTrackLaterEdits() {
        val editor = SubEditEditorState()
        editor.initialize(SubEditForm())
        completeForm.textValues().forEach { (key, value) ->
            editor.text[key].setTextAndPlaceCursorAtEnd(value)
        }
        editor.enabled = completeForm.enabled
        editor.autoUpdate = completeForm.autoUpdate
        editor.allowInsecureUrl = completeForm.allowInsecureUrl

        val submitted = SubEditAction.Save(editor.snapshot())
        SubField.entries.forEach { editor[it].setTextAndPlaceCursorAtEnd("later") }
        editor.enabled = true
        editor.autoUpdate = false
        editor.allowInsecureUrl = false

        assertEquals(completeForm, submitted.form)
        assertEquals("later", editor.snapshot().remarks)
    }

    @Test
    fun restoredDraftSelectionsAndFlagsWinOverLateInitialization() {
        val editor = SubEditEditorState()
        editor.initialize(completeForm)
        SubField.entries.forEachIndexed { index, field ->
            editor[field].edit { selection = TextRange(length, index.coerceAtMost(length)) }
        }
        val payload = checkNotNull(with(SubEditEditorState.Saver) { scope.save(editor) })
        editor[SubField.REMARKS].setTextAndPlaceCursorAtEnd("after save")
        editor.enabled = true

        val restored = checkNotNull(SubEditEditorState.Saver.restore(serializedCopy(payload)))
        assertFalse(restored.initialize(SubEditForm(remarks = "late load")))
        assertEquals(completeForm, restored.snapshot())
        SubField.entries.forEachIndexed { index, field ->
            val length = restored[field].text.length
            assertEquals(TextRange(length, index.coerceAtMost(length)), restored[field].selection)
        }
    }

    @Test
    fun savingBeforeLoadDoesNotTurnAnEmptyHolderIntoAnInitializedDraft() {
        val editor = SubEditEditorState()
        val payload = checkNotNull(with(SubEditEditorState.Saver) { scope.save(editor) })
        val restored = checkNotNull(SubEditEditorState.Saver.restore(payload))

        assertFalse(restored.text.isInitialized)
        assertTrue(restored.initialize(completeForm))
        assertFalse(restored.initialize(SubEditForm()))
        assertEquals(completeForm, restored.snapshot())
    }

    private val scope = object : SaverScope {
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
