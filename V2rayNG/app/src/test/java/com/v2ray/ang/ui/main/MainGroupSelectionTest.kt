package com.v2ray.ang.ui.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainGroupSelectionTest {

    @Test
    fun restoredPageCannotOverwriteSelectionBeforeAlignmentFinishes() = runTest {
        val pages = MutableStateFlow(0)
        val alignedPages = mutableListOf<Int>()
        val selections = mutableListOf<String>()
        val allowAlignment = CompletableDeferred<Unit>()
        val observer = backgroundScope.launch {
            observeGroupPagerSelection(
                groupIds = listOf("", "a", "b"),
                selectedGroupId = "b",
                settledPages = pages,
                alignPage = { index ->
                    alignedPages += index
                    allowAlignment.await()
                    pages.value = index
                },
                onSelect = { selections += it }
            )
        }
        runCurrent()

        assertEquals(listOf(2), alignedPages)
        assertEquals(0, pages.subscriptionCount.value)
        assertTrue(selections.isEmpty())

        allowAlignment.complete(Unit)
        runCurrent()
        assertEquals(2, pages.value)
        assertTrue(selections.isEmpty())

        pages.value = 1
        runCurrent()
        assertEquals(listOf("a"), selections)
        observer.cancelAndJoin()
    }

    @Test
    fun transientGroupGapDoesNotSelectAllAndReturnRealignsById() = runTest {
        val pages = MutableStateFlow(0)
        val alignedPages = mutableListOf<Int>()
        val selections = mutableListOf<String>()

        observeGroupPagerSelection(
            groupIds = listOf(""),
            selectedGroupId = "b",
            settledPages = pages,
            alignPage = { alignedPages += it },
            onSelect = { selections += it }
        )
        assertTrue(alignedPages.isEmpty())
        assertTrue(selections.isEmpty())
        assertEquals(0, pages.subscriptionCount.value)

        val observer = backgroundScope.launch {
            observeGroupPagerSelection(
                groupIds = listOf("", "b", "a"),
                selectedGroupId = "b",
                settledPages = pages,
                alignPage = { index ->
                    alignedPages += index
                    pages.value = index
                },
                onSelect = { selections += it }
            )
        }
        runCurrent()
        assertEquals(listOf(1), alignedPages)
        assertEquals(1, pages.value)
        assertTrue(selections.isEmpty())
        observer.cancelAndJoin()
    }

    @Test
    fun groupReorderRealignsBeforeObservingTheOldPageIndex() = runTest {
        val pages = MutableStateFlow(2)
        val selections = mutableListOf<String>()
        val observer = backgroundScope.launch {
            observeGroupPagerSelection(
                groupIds = listOf("", "b", "a"),
                selectedGroupId = "b",
                settledPages = pages,
                alignPage = { pages.value = it },
                onSelect = { selections += it }
            )
        }
        runCurrent()
        assertEquals(1, pages.value)
        assertTrue(selections.isEmpty())

        pages.value = 0
        runCurrent()
        // Selecting All deliberately is still a normal user selection.
        assertEquals(listOf(""), selections)
        observer.cancelAndJoin()
    }

    @Test
    fun outOfRangeSettledPagesAreIgnored() = runTest {
        val pages = MutableStateFlow(1)
        val selections = mutableListOf<String>()
        val observer = backgroundScope.launch {
            observeGroupPagerSelection(
                groupIds = listOf("", "a"),
                selectedGroupId = "a",
                settledPages = pages,
                alignPage = { pages.value = it },
                onSelect = { selections += it }
            )
        }
        runCurrent()
        pages.value = 5
        runCurrent()
        pages.value = -1
        runCurrent()
        assertTrue(selections.isEmpty())
        observer.cancelAndJoin()
    }
}
