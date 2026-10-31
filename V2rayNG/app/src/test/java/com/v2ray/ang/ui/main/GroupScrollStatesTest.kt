package com.v2ray.ang.ui.main

import androidx.compose.runtime.saveable.SaverScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class GroupScrollStatesTest {

    @Test
    fun saverRestoresIndependentListAndGridViewportsAndUnvisitedGroups() {
        val listSeeds = mapOf("a" to ScrollAnchor(120, 48, "guid-a"), "b" to ScrollAnchor(240, 12, "guid-b"))
        val gridSeeds = mapOf("a" to ScrollAnchor(62, 20, "grid-guid"))
        val holder = restore(listSeeds, gridSeeds)
        val state = holder.list("a")
        assertEquals(120, state.firstVisibleItemIndex)
        assertEquals(48, state.firstVisibleItemScrollOffset)
        assertSame(state, holder.list("a"))
        assertEquals(62, holder.grid("a").firstVisibleItemIndex)
        assertEquals(20, holder.grid("a").firstVisibleItemScrollOffset)

        val saved = checkNotNull(with(GroupScrollStates.Saver) { scope.save(holder) })
        val bytes = ByteArrayOutputStream().also { output ->
            ObjectOutputStream(output).use { it.writeObject(saved) }
        }.toByteArray()
        val payload = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
        val recreated = checkNotNull(GroupScrollStates.Saver.restore(payload))
        assertNotSame(state, recreated.list("a"))
        assertEquals(listSeeds["b"], recreated.pendingAnchor("b", false))
        assertEquals(240, recreated.list("b").firstVisibleItemIndex)
        assertEquals(62, recreated.grid("a").firstVisibleItemIndex)
    }

    @Test
    fun retainingGroupsAlsoPrunesUnvisitedSeeds() {
        val holder = restore(mapOf("keep" to ScrollAnchor(10, 4), "removed" to ScrollAnchor(90, 8)))
        holder.retain(setOf("keep"))
        val saved = checkNotNull(with(GroupScrollStates.Saver) { scope.save(holder) })
        val recreated = checkNotNull(GroupScrollStates.Saver.restore(saved))
        assertNull(recreated.pendingAnchor("removed", false))
        assertEquals(0, recreated.list("removed").firstVisibleItemIndex)
        assertEquals(10, recreated.list("keep").firstVisibleItemIndex)
    }

    @Test
    fun consumingOneLayoutDoesNotConsumeTheOtherAndLateResponsesAreIgnored() {
        val holder = restore(mapOf("a" to ScrollAnchor(10, 4)), mapOf("a" to ScrollAnchor(20, 8)))
        holder.finishRestore("a", false)
        assertNull(holder.pendingAnchor("a", false))
        assertEquals(ScrollAnchor(20, 8), holder.pendingAnchor("a", true))

        val first = holder.beginAnchorLookup("first")
        val second = holder.beginAnchorLookup("second")
        holder.cancelAnchorLookup("first")
        holder.resolveAnchorIndex("first", 999)
        holder.resolveAnchorIndex("second", 64)
        assertEquals(true, first.isCancelled)
        assertEquals(true, second.isCompleted)
    }

    private fun restore(
        lists: Map<String, ScrollAnchor>,
        grids: Map<String, ScrollAnchor> = emptyMap()
    ): GroupScrollStates = checkNotNull(GroupScrollStates.Saver.restore(
        arrayListOf(1, encodeAnchors(lists), encodeAnchors(grids))
    ))

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean =
            value is String || value is Int || value is List<*> && value.all { it != null && canBeSaved(it) }
    }
}
