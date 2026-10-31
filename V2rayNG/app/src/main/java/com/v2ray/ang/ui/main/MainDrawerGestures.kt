package com.v2ray.ang.ui.main

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign


/**
 * An ancestor observer, not an invisible edge overlay: vertical scrolling and ordinary Pager swipes
 * keep their original hit targets. Only an inward, horizontal edge drag claims the Initial pass;
 * after opening, horizontal drags anywhere in the drawer frame can close it. No system-gesture
 * exclusion is requested, so Android retains ownership of its back edge.
 */
internal fun Modifier.mainDrawerGestures(
    state: MainPushDrawerState,
    scope: CoroutineScope,
    isRtl: Boolean,
    edgeWidth: Float,
    maxDrawerWidth: Float,
    velocityThreshold: Float,
    enabled: Boolean
): Modifier = pointerInput(state, isRtl, edgeWidth, maxDrawerWidth, velocityThreshold, enabled) {
    if (!enabled) return@pointerInput
    val direction = if (isRtl) -1f else 1f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!state.canStartGesture()) return@awaitEachGesture
        val visibleOnDown = state.isVisible
        if (!visibleOnDown && !isMainDrawerEdgeStart(down.position.x, size.width.toFloat(), edgeWidth, isRtl)) {
            return@awaitEachGesture
        }
        val width = mainDrawerWidth(size.width.toFloat(), maxDrawerWidth)
        if (width <= 0f) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var distance = Offset.Zero
        var firstDelta: Float
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed || !state.canStartGesture()) return@awaitEachGesture
            tracker.addPosition(change.uptimeMillis, change.position)
            distance += change.positionChange()
            val dx = distance.x * direction
            val slop = viewConfiguration.touchSlop
            if (shouldStartMainDrawerDrag(dx, distance.y, slop, visibleOnDown)) {
                firstDelta = dx - sign(dx) * slop
                change.consume()
                break
            }
            // Yield the whole sequence, not just this event, to vertical scroll or an outward swipe.
            if (abs(distance.y) > slop || abs(dx) > slop) return@awaitEachGesture
            val final = awaitPointerEvent(PointerEventPass.Final)
            if (final.changes.any { it.id == down.id && it.isConsumed }) return@awaitEachGesture
        }

        // Pointer scopes are restricted suspending scopes. The UI-owned worker holds Foundation's
        // drag mutex while deltas arrive; closing the channel also handles platform cancellation.
        val deltas = Channel<Float>(Channel.UNLIMITED)
        var velocity = 0f
        var cancelled = true
        val drag = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                state.drag {
                    for (delta in deltas) dragBy(delta / width)
                }
                state.settle(velocity, width, velocityThreshold, cancelled)
            } finally {
                deltas.cancel()
            }
        }
        try {
            deltas.trySend(firstDelta)
            while (drag.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    velocity = tracker.calculateVelocity().x * direction
                    cancelled = false
                    change.consume()
                    break
                }
                if (deltas.trySend(change.positionChange().x * direction).isFailure) break
                change.consume()
            }
        } finally {
            deltas.close()
        }
    }
}
