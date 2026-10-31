package com.v2ray.ang.ui.main

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * One normalized position for touch, programmatic motion and predictive back. Foundation's drag
 * mutex serializes all three, so a finger can interrupt an animation without a second progress state.
 * Normalized coordinates also avoid rebuilding anchors or resizing the home page on rotation.
 */
@Stable
internal class MainPushDrawerState(
    initialOpen: Boolean,
    private var animationSpec: AnimationSpec<Float>
) {
    var progress by mutableFloatStateOf(if (initialOpen) 1f else 0f)
        private set
    var targetOpen by mutableStateOf(initialOpen)
        private set
    private var isDragging by mutableStateOf(false)
    private var isAnimating by mutableStateOf(false)
    var isBackInProgress by mutableStateOf(false)
        private set

    // Read through derivedStateOf at interaction boundaries, never directly in MainScreen.
    val isVisible: Boolean
        get() = progress > 0f || targetOpen || isDragging || isAnimating || isBackInProgress

    private val actions = MainDrawerActionGate()
    private val dragState = DraggableState { delta -> progress = (progress + delta).coerceIn(0f, 1f) }

    fun updateAnimationSpec(spec: AnimationSpec<Float>) {
        animationSpec = spec
    }

    fun canStartGesture(): Boolean = !actions.isPending && !isBackInProgress

    suspend fun open() {
        if (canStartGesture()) animateTo(true)
    }

    suspend fun close() {
        if (canStartGesture()) animateTo(false)
    }

    /** Retain an open drawer on the back stack without waiting for any animation before dispatch. */
    suspend fun navigate(action: () -> Unit) {
        if (isBackInProgress) return
        actions.run {
            dragState.drag(MutatePriority.PreventUserInput) {
                // Cancel an in-flight settle and save the open endpoint, even for a fast menu tap.
                // This needs no animation frame; only NavHost animates the destination change.
                targetOpen = true
                dragBy(1f - progress)
            }
            action()
        }
    }

    fun onDestinationResumed() {
        actions.onDestinationResumed()
    }

    suspend fun drag(block: suspend DragScope.() -> Unit) {
        dragState.drag(MutatePriority.UserInput) {
            isDragging = true
            try {
                block()
            } finally {
                isDragging = false
            }
        }
    }

    suspend fun settle(velocity: Float, width: Float, velocityThreshold: Float, cancelled: Boolean) {
        val open = if (cancelled) targetOpen else mainDrawerTargetOpen(progress, velocity, velocityThreshold)
        animateTo(open, if (cancelled || width <= 0f) 0f else velocity / width)
    }

    /** Cancellation restores the previous target, while a committed gesture finishes the close. */
    suspend fun closePredictively(events: Flow<Float>, onCancel: (Boolean) -> Unit) {
        if (!canStartGesture()) {
            events.collect { }
            return
        }
        val restoreOpen = targetOpen
        var committed = false
        try {
            dragState.drag(MutatePriority.PreventUserInput) {
                isBackInProgress = true
                val start = progress
                try {
                    events.collect { value ->
                        dragBy(start * (1f - value.coerceIn(0f, 1f)) - progress)
                    }
                    committed = true
                    animateInDrag(false)
                } finally {
                    isBackInProgress = false
                }
            }
        } catch (cancelled: CancellationException) {
            if (!committed) onCancel(restoreOpen)
            throw cancelled
        }
    }

    suspend fun restoreAfterCancelledBack(open: Boolean) {
        if (canStartGesture()) animateTo(open)
    }

    private suspend fun animateTo(open: Boolean, velocity: Float = 0f) {
        dragState.drag(MutatePriority.Default) { animateInDrag(open, velocity) }
    }

    private suspend fun DragScope.animateInDrag(open: Boolean, velocity: Float = 0f) {
        targetOpen = open
        isAnimating = true
        val destination = if (open) 1f else 0f
        try {
            if (progress != destination) {
                animate(
                    initialValue = progress,
                    targetValue = destination,
                    initialVelocity = velocity,
                    animationSpec = animationSpec
                ) { value, _ -> dragBy(value - progress) }
            }
            dragBy(destination - progress)
        } finally {
            isAnimating = false
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberMainPushDrawerState(initialOpen: Boolean = false): MainPushDrawerState {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val saver = remember(spec) {
        Saver<MainPushDrawerState, Boolean>(
            save = { it.targetOpen },
            restore = { MainPushDrawerState(it, spec) }
        )
    }
    val state = rememberSaveable(saver = saver) { MainPushDrawerState(initialOpen, spec) }
    SideEffect { state.updateAnimationSpec(spec) }
    return state
}
