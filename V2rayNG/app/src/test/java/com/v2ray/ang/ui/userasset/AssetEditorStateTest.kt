package com.v2ray.ang.ui.userasset

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class AssetEditorStateTest {
    private val completeForm = AssetForm("geosite.dat", "https://example.com/geosite.dat")

    @Test
    fun snapshotReadsBothLatestFieldsImmediatelyAndDoesNotChangeWithLaterEdits() {
        val editor = AssetEditorState()
        editor.initialize(AssetForm())
        editor[AssetField.REMARKS].setTextAndPlaceCursorAtEnd(completeForm.remarks)
        editor[AssetField.URL].setTextAndPlaceCursorAtEnd(completeForm.url)

        val submitted = UserAssetUrlAction.Save(editor.snapshot())
        AssetField.entries.forEach { editor[it].setTextAndPlaceCursorAtEnd("later") }

        assertEquals(AssetField.entries.map { it.name }.toSet(), completeForm.textValues().keys)
        assertEquals(completeForm, submitted.form)
        assertEquals(AssetForm("later", "later"), editor.snapshot())
    }

    @Test
    fun restorePreservesBothSelectionDirectionsAndDoesNotAcceptLateLoad() {
        val editor = AssetEditorState()
        editor.initialize(completeForm)
        editor[AssetField.REMARKS].edit { selection = TextRange(2, 5) }
        editor[AssetField.URL].edit { selection = TextRange(20, 8) }
        val payload = checkNotNull(with(AssetEditorState.Saver) { scope.save(editor) })
        editor[AssetField.URL].setTextAndPlaceCursorAtEnd("later")

        val restored = checkNotNull(AssetEditorState.Saver.restore(serializedCopy(payload)))
        assertFalse(restored.initialize(AssetForm("late", "load")))
        assertEquals(completeForm, restored.snapshot())
        assertEquals(TextRange(2, 5), restored[AssetField.REMARKS].selection)
        assertEquals(TextRange(20, 8), restored[AssetField.URL].selection)
    }

    @Test
    fun unloadedRestoreCanInitializeExactlyOnce() {
        val payload = checkNotNull(with(AssetEditorState.Saver) { scope.save(AssetEditorState()) })
        val restored = checkNotNull(AssetEditorState.Saver.restore(payload))

        assertFalse(restored.text.isInitialized)
        assertTrue(restored.initialize(completeForm))
        assertFalse(restored.initialize(AssetForm()))
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
