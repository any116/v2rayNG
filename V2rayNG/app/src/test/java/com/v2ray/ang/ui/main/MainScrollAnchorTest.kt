package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainScrollAnchorTest {

    @Test
    fun roundTripKeepsGroupIdentityOffsetGuidAndQuery() {
        val anchors = mapOf(
            "" to ScrollAnchor(120, 48, "guid-all", "filter"),
            "订阅/a" to ScrollAnchor(7, -12, "guid-1"),
            "sub-2" to ScrollAnchor(0, 12)
        )

        assertEquals(anchors, decodeAnchors(encodeAnchors(anchors)))
        assertTrue(decodeAnchors(encodeAnchors(emptyMap())).isEmpty())
    }

    @Test
    fun malformedAndTruncatedRecordsDoNotBreakRestore() {
        val valid = encodeAnchors(mapOf("a" to ScrollAnchor(9, 3, "guid-1", "query")))
        val truncated = ArrayList<Any>(valid).apply {
            add("b")
            add(4)
        }
        assertEquals(mapOf("a" to ScrollAnchor(9, 3, "guid-1", "query")), decodeAnchors(truncated))
        assertTrue(decodeAnchors(null).isEmpty())
        assertTrue(decodeAnchors("broken").isEmpty())
        assertTrue(decodeAnchors(listOf("a", -1, 0, "guid", "")).isEmpty())
        assertTrue(decodeAnchors(listOf("a", "index", 0, "guid", "")).isEmpty())
        assertTrue(decodeAnchors(listOf("a", 1, 0, 42, "")).isEmpty())
        assertTrue(decodeAnchors(listOf("a", 1, 0, "guid", 42)).isEmpty())
        val mixed = listOf(42, "index", 0, "", "", "c", 5, 1, "guid-3", "")
        assertEquals(mapOf("c" to ScrollAnchor(5, 1, "guid-3")), decodeAnchors(mixed))
    }

    @Test
    fun placeholderDoesNotLoseAKnownGuidOrAssociateItWithAnotherRow() {
        val old = ScrollAnchor(30, 10, "guid", "query")
        assertEquals(old.copy(offset = 22), mergeScrollAnchor(old, ScrollAnchor(30, 22, query = "query")))
        assertNull(mergeScrollAnchor(old, ScrollAnchor(31, 0, query = "query")).guid)
        assertNull(mergeScrollAnchor(old, ScrollAnchor(30, 0, query = "different")).guid)
    }

    @Test
    fun sqlCorrectionKeepsThePixelOffsetAndDeletedRowsFallBackWithinBounds() {
        val anchor = ScrollAnchor(100, 48, "guid", "query")
        assertEquals(ScrollAnchor(64, 48, "guid", "query"), restoredScrollAnchor(anchor, 64, 200))
        assertEquals(ScrollAnchor(100, 48, query = "query"), restoredScrollAnchor(anchor, null, 200))
        assertEquals(ScrollAnchor(19, 48, query = "query"), restoredScrollAnchor(anchor, null, 20))
        assertEquals(ScrollAnchor(0, 0, query = "query"), restoredScrollAnchor(anchor, null, 0))
        assertEquals(
            ScrollAnchor(100, 0, query = "query"),
            restoredScrollAnchor(ScrollAnchor(100, -5, "guid", "query"), null, 200)
        )
    }

    @Test
    fun snapshotSkipsAGroupWhoseQueryWasNeverRecorded() {
        val store = ScrollAnchorStore()
        // No captured scope: inventing "" would claim the unfiltered row set for a filtered viewport.
        assertTrue(store.snapshot(mapOf("a" to (40 to 16))).isEmpty())
    }

    @Test
    fun snapshotDropsGuidWhenTheLiveIndexMoved() {
        val store = ScrollAnchorStore(mapOf("a" to ScrollAnchor(10, 4, "guid-a", "q")))
        store.finishRestore("a")
        val saved = store.snapshot(mapOf("a" to (25 to 8)))

        assertEquals(ScrollAnchor(25, 8, query = "q"), saved["a"])
    }

    @Test
    fun snapshotKeepsGuidWhenOnlyTheOffsetMoved() {
        val store = ScrollAnchorStore(mapOf("a" to ScrollAnchor(10, 4, "guid-a", "q")))
        store.finishRestore("a")
        val saved = store.snapshot(mapOf("a" to (10 to 8)))

        assertEquals(ScrollAnchor(10, 8, "guid-a", "q"), saved["a"])
    }

    @Test
    fun pendingSeedsSurviveLoadingAndUnvisitedGroupsSurviveAnotherSave() {
        val a = ScrollAnchor(120, 48, "guid-a")
        val b = ScrollAnchor(240, 12, "guid-b")
        val store = ScrollAnchorStore(mapOf("a" to a, "b" to b))
        store.record("a", ScrollAnchor(0, 0))
        assertEquals(mapOf("a" to a, "b" to b), store.snapshot())

        store.finishRestore("a", a.copy(index = 121))
        store.record("a", ScrollAnchor(122, 8, "new-guid"))
        val saved = store.snapshot(mapOf("a" to (122 to 16)))
        assertEquals(ScrollAnchor(122, 16, "new-guid"), saved["a"])
        assertEquals(b, saved["b"])
        assertNull(store.pending("a"))
    }

    @Test
    fun pruningRemovesSeedsAndCapturedAnchorsAndCannotResurrectThem() {
        val store = ScrollAnchorStore(mapOf("a" to ScrollAnchor(10, 2), "removed" to ScrollAnchor(20, 4)))
        store.finishRestore("a")
        store.record("a", ScrollAnchor(11, 3, "guid"))
        store.retain(setOf("a"))
        assertEquals(mapOf("a" to ScrollAnchor(11, 3, "guid")), store.snapshot())
        assertNull(store.pending("removed"))
    }
}
