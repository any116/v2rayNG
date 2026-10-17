package com.v2ray.ang.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FormPagedDropdownQueryTest {

    @Test
    fun initialBusinessTextDoesNotBecomeASearch() = runTest {
        val field = TextFieldState("selected node")
        val queries = DropdownQueryState(field.text.toString(), "")
        val pushed = observe(field, queries)

        advanceTimeBy(500)
        runCurrent()

        assertTrue(pushed.isEmpty())
        assertFalse(queries.expanded)
    }

    @Test
    fun pickingThePendingTextSuppressesQueryWithoutAnotherTextEmission() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = observe(field, queries)
        edit(field, "a")
        runCurrent()
        advanceTimeBy(100)

        queries.pick(field, "a")
        advanceTimeBy(150)
        runCurrent()

        assertTrue(pushed.isEmpty())
        assertEquals("a", field.text.toString())
        assertFalse(queries.expanded)
    }

    @Test
    fun typedQueryIsPushedOnlyAfterTheDebounce() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = observe(field, queries)
        edit(field, "a")
        runCurrent()

        advanceTimeBy(249)
        runCurrent()
        assertTrue(pushed.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a"), pushed)
    }

    @Test
    fun pickingAnotherOptionClosesTheMenuAndDoesNotQueryThePick() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = observe(field, queries)
        edit(field, "a")
        runCurrent()
        advanceTimeBy(100)

        queries.pick(field, "another node")
        Snapshot.sendApplyNotifications()
        advanceTimeBy(500)
        runCurrent()

        assertTrue(pushed.isEmpty())
        assertEquals("another node", field.text.toString())
        assertFalse(queries.expanded)
    }

    @Test
    fun newerInputCancelsTheOldDebounceAndWaitsForItsOwnDelay() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = observe(field, queries)
        edit(field, "a")
        runCurrent()
        advanceTimeBy(100)

        edit(field, "ab")
        runCurrent()

        advanceTimeBy(150)
        runCurrent()
        assertTrue(pushed.isEmpty())

        advanceTimeBy(99)
        runCurrent()
        assertTrue(pushed.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("ab"), pushed)
    }

    @Test
    fun liveTextRejectsAStaleQueryBeforeTheCollectorObservesTheEdit() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = observe(field, queries)
        edit(field, "a")
        runCurrent()
        advanceTimeBy(100)

        // Deliberately withhold apply notifications until the old delay expires.
        field.setTextAndPlaceCursorAtEnd("ab")
        advanceTimeBy(150)
        runCurrent()
        assertTrue(pushed.isEmpty())

        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf("ab"), pushed)
    }

    @Test
    fun cancellingThePendingQueryPreventsItsPush() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = mutableListOf<String>()
        val pending = launch { observeDropdownQueries(field, queries, 250L, pushed::add) }
        runCurrent()
        edit(field, "a")
        runCurrent()
        advanceTimeBy(249)

        pending.cancel()
        advanceUntilIdle()

        assertTrue(pushed.isEmpty())
    }

    @Test
    fun inputAfterAPickStartsItsOwnSearch() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = observe(field, queries)
        queries.pick(field, "node")
        edit(field, "node-2")
        runCurrent()

        advanceTimeBy(250)
        runCurrent()

        assertEquals(listOf("node-2"), pushed)
    }

    @Test
    fun returningToTheLastObservedTextAfterAPickIsNotLostToConflation() = runTest {
        val field = TextFieldState("initial")
        val queries = DropdownQueryState("initial", "")
        val pushed = observe(field, queries)

        // The collector never sees the picked text, only a return to its last text emission.
        queries.pick(field, "picked")
        edit(field, "initial")
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(listOf("initial"), pushed)
    }

    @Test
    fun pickAndConflatedReturnToAPendingQueryStartANewFullDebounce() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = observe(field, queries)
        edit(field, "a")
        runCurrent()
        advanceTimeBy(100)

        queries.pick(field, "picked")
        edit(field, "a")
        runCurrent()

        advanceTimeBy(150)
        runCurrent()
        assertTrue(pushed.isEmpty())
        advanceTimeBy(99)
        runCurrent()
        assertTrue(pushed.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a"), pushed)
    }

    @Test
    fun aPickBeforeTheCollectorSeesTypingStillSuppressesThatQuery() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val pushed = observe(field, queries)

        edit(field, "node")
        queries.pick(field, "node")
        advanceTimeBy(500)
        runCurrent()

        assertTrue(pushed.isEmpty())
        assertFalse(queries.expanded)
    }

    @Test
    fun restoredPickKeepsItsDraftAndSuppression() = runTest {
        val field = TextFieldState("old")
        val queries = DropdownQueryState("old", "filter")
        queries.pick(field, "picked node")

        val restored = restore(field, queries)
        val pushed = observe(restored.first, restored.second)
        advanceTimeBy(500)
        runCurrent()

        assertEquals("picked node", restored.first.text.toString())
        assertTrue(pushed.isEmpty())
        assertFalse(restored.second.expanded)
    }

    @Test
    fun restoredPendingSearchDoesNotReopenADismissedMenu() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val originalPushes = observe(field, queries)
        edit(field, "draft")
        runCurrent()
        queries.dismiss(field, resetQuery = false)
        val restored = restore(field, queries)
        val pushed = observe(restored.first, restored.second)

        advanceTimeBy(250)
        runCurrent()

        assertEquals(listOf("draft"), originalPushes)
        assertEquals(listOf("draft"), pushed)
        assertFalse(restored.second.expanded)
    }

    @Test
    fun restoredSubmittedQueryIsReappliedToANewOwnerQuery() = runTest {
        val field = TextFieldState()
        val queries = DropdownQueryState("", "")
        val originalPushes = observe(field, queries)
        edit(field, "draft")
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        val restored = restore(field, queries)
        val pushed = observe(restored.first, restored.second)
        advanceTimeBy(500)
        runCurrent()

        assertEquals(listOf("draft"), originalPushes)
        assertEquals(listOf("draft"), pushed)
    }

    @Test
    fun resetOnDismissClearsOnlyTheQueryAndKeepsTheFieldText() = runTest {
        val field = TextFieldState("selected node")
        val queries = DropdownQueryState(field.text.toString(), "filter")
        val pushed = observe(field, queries)

        queries.dismiss(field, resetQuery = true)
        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceTimeBy(250)
        runCurrent()

        assertEquals(listOf(""), pushed)
        assertEquals("selected node", field.text.toString())
        assertFalse(queries.expanded)
    }

    @Test
    fun malformedSavedQueryMetadataIsRejectedWithoutThrowing() {
        assertNull(DropdownQueryState.Saver.restore(listOf(true)))
    }

    private fun restore(
        field: TextFieldState,
        queries: DropdownQueryState
    ): Pair<TextFieldState, DropdownQueryState> {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val savedFields = checkNotNull(with(InputFieldStatesSaver) { scope.save(listOf(field)) })
        val savedQueries = checkNotNull(with(DropdownQueryState.Saver) { scope.save(queries) })
        return checkNotNull(InputFieldStatesSaver.restore(savedFields)).single() to
            checkNotNull(DropdownQueryState.Saver.restore(savedQueries))
    }

    private fun TestScope.observe(field: TextFieldState, queries: DropdownQueryState): List<String> {
        val pushed = mutableListOf<String>()
        backgroundScope.launch {
            observeDropdownQueries(field, queries, 250L, pushed::add)
        }
        runCurrent()
        return pushed
    }

    private fun edit(field: TextFieldState, text: String) {
        field.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }
}
