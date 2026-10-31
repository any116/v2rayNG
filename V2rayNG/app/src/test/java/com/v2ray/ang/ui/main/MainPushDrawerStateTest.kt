package com.v2ray.ang.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainPushDrawerStateTest {

    private class TestFrameClock : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            time += 16_000_000L
            return onFrame(time)
        }
    }

    @Test
    fun openAndCloseFinishAtExactEndpoints() = runTest {
        withContext(TestFrameClock()) {
            val state = MainPushDrawerState(false, snap())
            assertFalse(state.isVisible)
            state.open()
            assertEquals(1f, state.progress)
            assertTrue(state.targetOpen)
            state.close()
            assertEquals(0f, state.progress)
            assertFalse(state.targetOpen)
            assertFalse(state.isVisible)
        }
    }

    @Test
    fun touchInterruptsAnimationFromItsCurrentPosition() = runTest {
        val state = MainPushDrawerState(false, tween(200, easing = LinearEasing))
        val animation = launch(TestFrameClock()) { state.open() }
        advanceTimeBy(80)
        runCurrent()
        val position = state.progress
        assertTrue(position > 0f && position < 1f)
        state.drag { dragBy(-0.1f) }
        animation.join()
        assertTrue(animation.isCancelled)
        assertEquals((position - 0.1f).coerceAtLeast(0f), state.progress, 0.0001f)
    }

    @Test
    fun dragCannotMovePastEitherEndpoint() = runTest {
        val state = MainPushDrawerState(false, snap())
        state.drag { dragBy(2f) }
        assertEquals(1f, state.progress)
        state.drag { dragBy(-3f) }
        assertEquals(0f, state.progress)
    }

    @Test
    fun predictiveCommitKeepsTheHandlerAliveAtZeroUntilCommit() = runTest {
        val state = MainPushDrawerState(true, snap())
        state.closePredictively(
            events = flow {
                emit(0.25f)
                assertEquals(0.75f, state.progress)
                emit(1f)
                assertEquals(0f, state.progress)
                assertTrue(state.isVisible)
                assertTrue(state.isBackInProgress)
            },
            onCancel = { error("Committed back must not restore") }
        )
        assertFalse(state.targetOpen)
        assertFalse(state.isVisible)
        assertFalse(state.isBackInProgress)
    }

    @Test
    fun cancelledPredictiveBackRestoresOpenWithoutNavigating() = runTest {
        withContext(TestFrameClock()) {
            val state = MainPushDrawerState(true, snap())
            var restoreOpen: Boolean? = null
            try {
                state.closePredictively(
                    events = flow {
                        emit(0.6f)
                        throw CancellationException("Gesture cancelled")
                    },
                    onCancel = { restoreOpen = it }
                )
            } catch (_: CancellationException) {
                // Matches the UI handler's cancellation path; rollback belongs to its UI scope.
            }
            assertEquals(true, restoreOpen)
            assertFalse(state.isBackInProgress)
            state.restoreAfterCancelledBack(requireNotNull(restoreOpen))
            assertEquals(1f, state.progress)
            assertTrue(state.isVisible)
        }
    }

    @Test
    fun cancelledDragReturnsToThePreviousTarget() = runTest {
        withContext(TestFrameClock()) {
            val state = MainPushDrawerState(true, snap())
            state.drag { dragBy(-0.8f) }
            state.settle(velocity = -1000f, width = 300f, velocityThreshold = 125f, cancelled = true)
            assertEquals(1f, state.progress)
        }
    }

    @Test
    fun navigationClosesFirstAndCannotBeInterruptedByAnotherOpenRequest() = runTest {
        withContext(TestFrameClock()) {
            val state = MainPushDrawerState(true, tween(100))
            var dispatched = false
            val navigation = launch {
                state.closeThen {
                    assertEquals(0f, state.progress)
                    dispatched = true
                }
            }
            runCurrent()
            assertFalse(state.canStartGesture())
            state.open()
            assertFalse(dispatched)
            navigation.join()
            assertTrue(dispatched)
            assertFalse(state.isVisible)
            assertFalse(state.restoreHomeFocusOnClose)
            assertTrue(state.canStartGesture())
            state.open()
            assertTrue(state.restoreHomeFocusOnClose)
        }
    }
}
