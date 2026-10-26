package com.v2ray.ang.ui.components

import android.os.Build
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** State shared by the platform back observer and Compose-owned back handlers. */
@Stable
class PredictiveBackProgressState internal constructor(
    internal val enabled: Boolean
) {
    /** The latest Compose-owned gesture progress; keep high-frequency reads in draw/layout lambdas. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** True during a gesture or its committed destination/window transition. */
    var isInProgress by mutableStateOf(false)
        private set

    internal fun onBackStarted() {
        isInProgress = true
        progress = 0f
    }

    internal fun onBackProgressed(value: Float) {
        progress = value.coerceIn(0f, 1f)
    }

    /** Keeps expensive animations suppressed after commit until the owner is visible again. */
    internal fun onBackCommitted() {
        isInProgress = true
        progress = 1f
    }

    internal fun onBackFinished() {
        isInProgress = false
        progress = 0f
    }
}

/** Progress state for a predictive back gesture, or `null` outside [PredictiveBackLayout]. */
val LocalPredictiveBackProgress = staticCompositionLocalOf<PredictiveBackProgressState?> { null }

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
        PredictiveBackProgressState(
            enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
    }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(view, state) {
        val registration = if (state.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Api36Impl.register(view, state)
        } else {
            null
        }
        onDispose { registration?.invoke() }
    }
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (state.enabled) {
                when (event) {
                    // Android 14/15 expose no non-consuming progress callback. ON_PAUSE is the
                    // first common lifecycle signal after a committed system-back transition.
                    Lifecycle.Event.ON_PAUSE -> state.onBackCommitted()
                    Lifecycle.Event.ON_RESUME -> state.onBackFinished()
                    else -> Unit
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalPredictiveBackProgress provides state) {
        Box(modifier.fillMaxSize()) {
            content()
        }
    }
}

/**
 * Dispatches [onBack] only when a gesture commits, without transforming the Activity content.
 *
 * The callback returns `true` when it starts a destination pop. In that case the state stays active
 * until the destination lifecycle reports that the previous screen is resumed; local back work can
 * return `false` and finish the state immediately.
 */
@Composable
fun AppBackHandler(enabled: Boolean = true, onBack: () -> Boolean) {
    val state = LocalPredictiveBackProgress.current
    val predictive = state?.enabled == true
    val currentOnBack by rememberUpdatedState(onBack)

    // Compose both handlers unconditionally so changing the preference cannot reorder callbacks.
    BackHandler(enabled = enabled && !predictive, onBack = { currentOnBack() })
    PredictiveBackHandler(enabled = enabled && predictive) { events ->
        state?.onBackStarted()
        var committed = false
        try {
            events.collect { event -> state?.onBackProgressed(event.progress) }
            committed = true
            if (!currentOnBack()) state?.onBackFinished()
        } finally {
            if (!committed) state?.onBackFinished()
        }
    }
}

/** Registers the Android 16 observer which sees a committed root back without consuming it. */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private object Api36Impl {
    fun register(view: View, state: PredictiveBackProgressState): (() -> Unit)? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = OnBackInvokedCallback {
            // The observer is commit-only; the system owns the preview and the final transition.
            state.onBackCommitted()
        }
        return try {
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_SYSTEM_NAVIGATION_OBSERVER,
                callback
            )
            val unregister = {
                dispatcher.unregisterOnBackInvokedCallback(callback)
            }
            unregister
        } catch (ignored: IllegalArgumentException) {
            // The observer is flag-gated on early Android 16 releases; keep normal back intact.
            null
        }
    }
}
