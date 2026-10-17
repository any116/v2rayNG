package com.v2ray.ang.ui.main

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import com.v2ray.ang.ui.components.closeSearchField
import com.v2ray.ang.ui.components.observeSearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Covers the shared production search helper at each of the four screen call sites. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchInputOwnershipTest {

    @Test
    fun initialDraftAndLatestEditsAreEmittedWithoutChangingSelection() = eachSearch { search ->
        val input = TextFieldState("restored draft", TextRange(12, 2))
        val queries = mutableListOf<String>()
        backgroundScope.launch { search.observe(input, { true }, { queries += it }) }
        settle()
        assertEquals(listOf("restored draft"), queries, search.name)
        assertEquals(TextRange(12, 2), input.selection, search.name)

        input.edit { selection = TextRange(3) }
        settle()
        assertEquals(listOf("restored draft"), queries, search.name)

        input.setTextAndPlaceCursorAtEnd("intermediate")
        input.setTextAndPlaceCursorAtEnd("latest")
        input.edit { selection = TextRange(5, 1) }
        settle()
        assertEquals(listOf("restored draft", "latest"), queries, search.name)
        assertEquals(TextRange(5, 1), input.selection, search.name)
    }

    @Test
    fun inactiveInputIsNotSubmittedAndOpeningUsesItsCurrentTextUnmodified() = eachSearch { search ->
        val input = TextFieldState("  [literal.*  ", TextRange(10, 2))
        var active by mutableStateOf(false)
        val queries = mutableListOf<String>()
        backgroundScope.launch { search.observe(input, { active }, { queries += it }) }
        settle()
        assertTrue(queries.isEmpty(), search.name)

        active = true
        settle()
        assertEquals(listOf("  [literal.*  "), queries, search.name)
        assertEquals(TextRange(10, 2), input.selection, search.name)
    }

    @Test
    fun closeClearsSynchronouslyAndDropsPendingInputBeforeReopening() = eachSearch { search ->
        val input = TextFieldState("applied")
        var active by mutableStateOf(true)
        val queries = mutableListOf<String>()
        backgroundScope.launch { search.observe(input, { active }, { queries += it }) }
        settle()

        input.setTextAndPlaceCursorAtEnd("not collected yet")
        Snapshot.sendApplyNotifications()
        var closed = false
        search.close(input) {
            assertEquals("", input.text.toString(), search.name)
            assertEquals(TextRange.Zero, input.selection, search.name)
            active = false
            closed = true
        }
        assertTrue(closed, search.name)
        settle()
        assertEquals(listOf("applied"), queries, search.name)

        active = true
        settle()
        assertEquals(listOf("applied", ""), queries, search.name)
        input.setTextAndPlaceCursorAtEnd("new session")
        settle()
        assertEquals(listOf("applied", "", "new session"), queries, search.name)
    }

    @Test
    fun clearingAnOpenInputEmitsEmptyImmediatelyAndCancellingStopsObservation() = eachSearch { search ->
        val input = TextFieldState("filter")
        val queries = mutableListOf<String>()
        val job = backgroundScope.launch { search.observe(input, { true }, { queries += it }) }
        settle()

        input.clearText()
        settle()
        assertEquals(listOf("filter", ""), queries, search.name)

        job.cancel()
        input.setTextAndPlaceCursorAtEnd("disposed root")
        settle()
        assertEquals(listOf("filter", ""), queries, search.name)
    }

    @Test
    fun foundationSaverRestoresDraftAndDirectedSelectionBeforeFirstQuery() = eachSearch { search ->
        val original = TextFieldState("draft not yet applied", TextRange(18, 3))
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = checkNotNull(with(TextFieldState.Saver) { scope.save(original) })
        original.clearText()
        val restored = checkNotNull(TextFieldState.Saver.restore(saved))
        val queries = mutableListOf<String>()
        backgroundScope.launch { search.observe(restored, { true }, { queries += it }) }
        settle()

        assertEquals(listOf("draft not yet applied"), queries, search.name)
        assertEquals(TextRange(18, 3), restored.selection, search.name)
    }

    private fun eachSearch(block: suspend TestScope.(Search) -> Unit) {
        searches.forEach { search -> runTest { block(search) } }
    }

    private fun TestScope.settle() {
        Snapshot.sendApplyNotifications()
        runCurrent()
    }

    private data class Search(
        val name: String,
        val observe: suspend (TextFieldState, () -> Boolean, (String) -> Unit) -> Unit,
        val close: (TextFieldState, () -> Unit) -> Unit
    )

    private val searches = listOf(Search("shared", ::observeSearchQuery, ::closeSearchField))
}
