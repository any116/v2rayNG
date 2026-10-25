package com.v2ray.ang.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

@Stable
private class PredictiveBackState(val enabled: Boolean)

private val LocalPredictiveBack = staticCompositionLocalOf<PredictiveBackState?> { null }

/**
 * Provides the app-owned back handler policy without adding a second content transition.
 *
 * Activity-level slide/fade transitions are configured by the base Activity. This provider only
 * selects the callback implementation: screens with app-owned back work can finish with their
 * result contract, while ordinary screens leave the gesture to Android's Activity dispatcher.
 */
@Composable
fun PredictiveBackLayout(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state = remember(enabled) {
        PredictiveBackState(enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }
    CompositionLocalProvider(LocalPredictiveBack provides state) {
        Box(modifier.fillMaxSize()) {
            content()
        }
    }
}

/** Dispatches [onBack] only when a gesture commits, without transforming the Activity content. */
@Composable
fun AppBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val state = LocalPredictiveBack.current
    val predictive = state?.enabled == true
    val currentOnBack by rememberUpdatedState(onBack)

    // Compose both handlers unconditionally so changing the preference cannot reorder callbacks.
    BackHandler(enabled = enabled && !predictive, onBack = currentOnBack)
    PredictiveBackHandler(enabled = enabled && predictive) { events ->
        events.collect { }
        currentOnBack()
    }
}
