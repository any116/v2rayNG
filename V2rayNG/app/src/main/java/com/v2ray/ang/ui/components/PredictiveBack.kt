package com.v2ray.ang.ui.components

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val BackScaleReduction = 0.1f
private val BackTranslation = 24.dp

@Stable
private class PredictiveBackState(val enabled: Boolean) {
    val progress = Animatable(0f)
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
    var resetJob: Job? = null
}

private val LocalPredictiveBack = staticCompositionLocalOf<PredictiveBackState?> { null }

/**
 * Animates app-owned Back actions without bypassing their result/persistence contracts.
 * Gesture frames only invalidate the graphics layer; business state stays in each ViewModel.
 * Activity exits without a screen handler are left to the system's destination preview.
 */
@Composable
fun PredictiveBackLayout(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Android 14 introduced progress/cancellation callbacks for app-owned animations.
    val state = remember(enabled) {
        PredictiveBackState(enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }
    val backShape = MaterialTheme.shapes.extraLarge
    val translation = with(LocalDensity.current) { BackTranslation.toPx() }
    CompositionLocalProvider(LocalPredictiveBack provides state) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val progress = state.progress.value
                scaleX = 1f - BackScaleReduction * progress
                scaleY = 1f - BackScaleReduction * progress
                val direction = if (state.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                translationX = direction * translation * progress
                shape = backShape
                clip = progress > 0f
            }) {
                content()
            }
        }
    }
}

/** Dispatches [onBack] only when a gesture commits; cancellation only restores the visual state. */
@Composable
fun AppBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val state = LocalPredictiveBack.current
    val predictive = state?.enabled == true
    val currentOnBack by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()

    // Always compose both handlers so their priority does not change when the preference changes.
    BackHandler(enabled = enabled && !predictive, onBack = onBack)
    PredictiveBackHandler(enabled = enabled && predictive) { events ->
        val animation = checkNotNull(state)
        animation.resetJob?.cancel()
        try {
            events.collect { event ->
                animation.swipeEdge = event.swipeEdge
                animation.progress.snapTo(event.progress.coerceIn(0f, 1f))
            }
            currentOnBack()
        } finally {
            // The gesture coroutine is cancelled on abort; use the composition's scope for reset.
            animation.resetJob = scope.launch { animation.progress.animateTo(0f) }
        }
    }
}
