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
    fun touchOpenAndCloseClearFocusWithoutCreatingAPersistentStateLayer() {
        assertEquals(MainDrawerFocusAction.CLEAR, mainDrawerFocusAction(true, false, true, false))
        assertEquals(MainDrawerFocusAction.CLEAR, mainDrawerFocusAction(false, true, true, false))
    }

    @Test
    fun closedColdStartDoesNotStealFocusFromRestoredSearch() {
        assertEquals(MainDrawerFocusAction.NONE, mainDrawerFocusAction(false, false, true, false))
        assertEquals(MainDrawerFocusAction.NONE, mainDrawerFocusAction(false, false, true, true))
    }

    @Test
    fun keyboardInputStillMovesFocusToDrawerAndBack() {
        assertEquals(MainDrawerFocusAction.DRAWER, mainDrawerFocusAction(true, false, true, true))
        assertEquals(MainDrawerFocusAction.HOME, mainDrawerFocusAction(false, true, true, true))
    }

    @Test
    fun backgroundHomeNeverStealsFocusDuringDestinationTransitions() {
        assertEquals(MainDrawerFocusAction.NONE, mainDrawerFocusAction(true, true, false, true))
        assertEquals(MainDrawerFocusAction.NONE, mainDrawerFocusAction(false, true, false, false))
    }

    @Test
    fun actionDispatchesImmediatelyAndDuplicateClicksWaitForResume() = runTest {
        val gate = MainDrawerActionGate()
        val actions = mutableListOf<Int>()
        gate.run { actions += 1 }
        gate.run { actions += 2 }
        assertEquals(listOf(1), actions)
        assertTrue(gate.isPending)
        gate.onDestinationResumed()
        gate.run { actions += 3 }
        assertEquals(listOf(1, 3), actions)
    }

    @Test
    fun cancelledDispatchReleasesTheGate() = runTest {
        val gate = MainDrawerActionGate()
        val ready = CompletableDeferred<Unit>()
        val actions = mutableListOf<Int>()
        val first = launch { gate.run { ready.await(); actions += 1 } }
        runCurrent()
        first.cancelAndJoin()
        assertTrue(actions.isEmpty())
        assertFalse(gate.isPending)
        gate.run { actions += 2 }
        assertEquals(listOf(2), actions)
    }

    @Test
    fun failedActionAlsoReleasesTheGate() = runTest {
        val gate = MainDrawerActionGate()
        try {
            gate.run { error("Navigation failed") }
        } catch (_: IllegalStateException) {
            // A failure is propagated, but must not permanently disable the menu.
        }
        assertFalse(gate.isPending)
        var dispatched = false
        gate.run { dispatched = true }
        assertTrue(dispatched)
    }
}
