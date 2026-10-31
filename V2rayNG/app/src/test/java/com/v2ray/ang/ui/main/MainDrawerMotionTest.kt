package com.v2ray.ang.ui.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainDrawerMotionTest {

    @Test
    fun slowReleaseUsesHalfwayPoint() {
        assertFalse(mainDrawerTargetOpen(0.49f, 0f, 125f))
        assertTrue(mainDrawerTargetOpen(0.5f, 0f, 125f))
        assertTrue(mainDrawerTargetOpen(0.8f, -100f, 125f))
    }

    @Test
    fun flingDirectionOverridesPosition() {
        assertTrue(mainDrawerTargetOpen(0.1f, 125f, 125f))
        assertFalse(mainDrawerTargetOpen(0.9f, -125f, 125f))
    }

    @Test
    fun releaseAtAnEndpointDoesNotReopenOrBounce() {
        assertFalse(mainDrawerTargetOpen(0f, 1000f, 125f))
        assertTrue(mainDrawerTargetOpen(1f, -1000f, 125f))
    }

    @Test
    fun openingGestureStartsOnlyAtTheLogicalStartEdge() {
        assertTrue(isMainDrawerEdgeStart(12f, 400f, 24f, false))
        assertFalse(isMainDrawerEdgeStart(100f, 400f, 24f, false))
        assertTrue(isMainDrawerEdgeStart(388f, 400f, 24f, true))
        assertFalse(isMainDrawerEdgeStart(12f, 400f, 24f, true))
        assertFalse(isMainDrawerEdgeStart(-1f, 400f, 24f, false))
        assertFalse(isMainDrawerEdgeStart(401f, 400f, 24f, true))
        assertFalse(isMainDrawerEdgeStart(0f, 0f, 24f, false))
    }

    @Test
    fun drawerWidthKeepsAPreviewOfHomeAndCapsWideWindows() {
        assertEquals(300f, mainDrawerWidth(400f, 360f))
        assertEquals(360f, mainDrawerWidth(1000f, 360f))
        assertEquals(150f, mainDrawerWidth(200f, 360f))
        assertEquals(0f, mainDrawerWidth(0f, 360f))
    }

    @Test
    fun verticalOrOutwardMovementDoesNotClaimTheEdge() {
        assertFalse(shouldStartMainDrawerDrag(12f, 20f, 8f, false))
        assertFalse(shouldStartMainDrawerDrag(-20f, 0f, 8f, false))
        assertFalse(shouldStartMainDrawerDrag(6f, 0f, 8f, false))
        assertTrue(shouldStartMainDrawerDrag(20f, 2f, 8f, false))
        assertTrue(shouldStartMainDrawerDrag(-20f, 2f, 8f, true))
    }

    @Test
    fun actionWaitsForCloseAndRepeatedClicksAreIgnored() = runTest {
        val gate = MainDrawerActionGate()
        val closed = CompletableDeferred<Unit>()
        val actions = mutableListOf<Int>()
        val first = launch { gate.run(close = { closed.await() }, action = { actions += 1 }) }
        runCurrent()
        gate.run(close = { error("Duplicate close") }, action = { actions += 2 })
        assertTrue(actions.isEmpty())
        assertTrue(gate.isRunning)
        closed.complete(Unit)
        first.join()
        assertEquals(listOf(1), actions)
        assertFalse(gate.isRunning)
    }

    @Test
    fun cancelledCloseDoesNotNavigateAndReleasesTheGate() = runTest {
        val gate = MainDrawerActionGate()
        val closed = CompletableDeferred<Unit>()
        val actions = mutableListOf<Int>()
        val first = launch { gate.run(close = { closed.await() }, action = { actions += 1 }) }
        runCurrent()
        first.cancelAndJoin()
        assertTrue(actions.isEmpty())
        assertFalse(gate.isRunning)
        gate.run(close = {}, action = { actions += 2 })
        assertEquals(listOf(2), actions)
    }

    @Test
    fun failedActionAlsoReleasesTheGate() = runTest {
        val gate = MainDrawerActionGate()
        try {
            gate.run(close = {}, action = { error("Navigation failed") })
        } catch (_: IllegalStateException) {
            // A failure is propagated, but must not permanently disable the menu.
        }
        assertFalse(gate.isRunning)
        var dispatched = false
        gate.run(close = {}, action = { dispatched = true })
        assertTrue(dispatched)
    }
}
