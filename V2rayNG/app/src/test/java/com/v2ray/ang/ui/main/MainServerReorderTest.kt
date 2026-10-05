package com.v2ray.ang.ui.main

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainServerReorderTest {

    @Test
    fun slowDownwardMoveAndReversalWaitForThePresentedOrder() = runTest {
        val rows = MutableStateFlow(listOf("a", "b", "c"))
        val actions = mutableListOf<MainAction>()
        val downAction = MainAction.MoveServer("group", "a", 1)
        val down = async {
            moveServerAndAwaitPresentation(downAction, { actions += it }, rows.map { it.getOrNull(1) })
        }
        runCurrent()

        assertEquals(listOf(downAction), actions)
        assertFalse(down.isCompleted)
        advanceTimeBy(400)
        // An unrelated paging update must not release the move while the dragged row is still above b.
        rows.value = listOf("a", "b", "other")
        runCurrent()
        assertFalse(down.isCompleted)

        rows.value = listOf("b", "a", "c")
        runCurrent()
        down.await()

        val upAction = MainAction.MoveServer("group", "a", 0)
        val up = async {
            moveServerAndAwaitPresentation(upAction, { actions += it }, rows.map { it.getOrNull(0) })
        }
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertFalse(up.isCompleted)
        assertEquals(listOf(downAction, upAction), actions)

        rows.value = listOf("a", "b", "c")
        runCurrent()
        up.await()
    }

    @Test
    fun placeholdersDoNotCompleteTheMove() = runTest {
        val targetGuid = MutableStateFlow<String?>("b")
        val move = async {
            moveServerAndAwaitPresentation(MainAction.MoveServer("group", "a", 1), {}, targetGuid)
        }
        runCurrent()

        targetGuid.value = null
        runCurrent()
        assertFalse(move.isCompleted)

        targetGuid.value = "a"
        move.await()
    }

    @Test
    fun failedPresentationCancelsInsteadOfReturningWithAnUnappliedMove() = runTest {
        val targetGuid = MutableStateFlow<String?>("b")
        val move = async {
            moveServerAndAwaitPresentation(MainAction.MoveServer("group", "a", 1), {}, targetGuid)
        }
        advanceUntilIdle()

        assertTrue(move.isCancelled)
        assertTrue(runCatching { move.await() }.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(0, targetGuid.subscriptionCount.value)
    }

    @Test
    fun leavingThePageCancelsThePresentationWait() = runTest {
        val targetGuid = MutableStateFlow<String?>("b")
        var dispatchCount = 0
        val move = async {
            moveServerAndAwaitPresentation(
                MainAction.MoveServer("group", "a", 1),
                { dispatchCount++ },
                targetGuid
            )
        }
        runCurrent()
        move.cancelAndJoin()

        assertEquals(1, dispatchCount)
        assertEquals(0, targetGuid.subscriptionCount.value)
    }
}
