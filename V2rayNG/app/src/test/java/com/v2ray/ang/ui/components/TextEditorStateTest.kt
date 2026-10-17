package com.v2ray.ang.ui.components

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextEditorStateTest {
    @Test
    fun initializationKeepsFieldIdentityAndOnlyRunsOnce() {
        val editor = TextEditorState(mapOf("name" to ""))
        val name = editor["name"]

        assertTrue(editor.initialize(mapOf("name" to "loaded")))
        assertSame(name, editor["name"])
        name.setTextAndPlaceCursorAtEnd("draft")
        assertFalse(editor.initialize(mapOf("name" to "late load")))
        assertEquals("draft", editor.text("name"))
    }

    @Test
    fun snapshotReadsFinalBuffersWithoutCollectionAndDoesNotTrackLaterEdits() {
        val editor = TextEditorState()
        editor.initialize(mapOf("name" to "initial", "url" to "initial url"))
        editor["name"].setTextAndPlaceCursorAtEnd("last character")
        editor["url"].setTextAndPlaceCursorAtEnd("")

        val submitted = editor.snapshot()
        editor["name"].setTextAndPlaceCursorAtEnd("later")

        assertEquals(mapOf("name" to "last character", "url" to ""), submitted)
    }

    @Test
    fun saverRestoresIndependentTextAndDirectedSelectionsWithoutAcceptingALateLoad() {
        val editor = TextEditorState()
        editor.initialize(mapOf("name" to "first\nsecond", "url" to "https://example.test"))
        editor["name"].edit { selection = TextRange(10, 1) }
        editor["url"].edit { selection = TextRange(4) }

        val saved = checkNotNull(with(TextEditorState.Saver) { scope.save(editor) })
        val restored = checkNotNull(TextEditorState.Saver.restore(saved))
        editor["name"].setTextAndPlaceCursorAtEnd("after snapshot")

        assertNotSame(editor["name"], restored["name"])
        assertTrue(restored.isInitialized)
        assertFalse(restored.initialize(mapOf("name" to "late load")))
        assertEquals("first\nsecond", restored.text("name"))
        assertEquals(TextRange(10, 1), restored["name"].selection)
        assertEquals(TextRange(4), restored["url"].selection)
    }

    @Test
    fun savingBeforeLoadDoesNotConvertDefaultsIntoAnInitializedDraft() {
        val editor = TextEditorState(mapOf("name" to "default"))
        val saved = checkNotNull(with(TextEditorState.Saver) { scope.save(editor) })
        val restored = checkNotNull(TextEditorState.Saver.restore(saved))

        assertFalse(restored.isInitialized)
        assertTrue(restored.initialize(mapOf("name" to "loaded")))
        assertEquals("loaded", restored.text("name"))
    }

    @Test
    fun alignToAddsNewSchemaFieldsAndDropsRemovedFieldsAfterRestore() {
        val editor = TextEditorState(mapOf("old" to "draft"))
        editor.initialize(mapOf("old" to "draft"))
        val saved = checkNotNull(with(TextEditorState.Saver) { scope.save(editor) })
        val restored = checkNotNull(TextEditorState.Saver.restore(saved))

        restored.alignTo(setOf("old", "new"))

        assertEquals("draft", restored.text("old"))
        assertEquals("", restored.text("new"))
        assertEquals("", restored.text("removed"))
    }

    @Test
    fun incompleteRestoredSchemaIsMarkedForFreshInitialization() {
        val editor = TextEditorState(mapOf("old" to "draft"))
        editor.initialize(mapOf("old" to "draft"))
        val saved = checkNotNull(with(TextEditorState.Saver) { scope.save(editor) })
        val restored = checkNotNull(TextEditorState.Saver.restore(saved))

        assertFalse(restored.reconcileKeys(mapOf("old" to "", "new" to "default")))
        assertEquals("default", restored.text("new"))
    }

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }
}
