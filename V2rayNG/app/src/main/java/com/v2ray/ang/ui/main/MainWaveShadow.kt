package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.sqrt

private val WaveBandWidth = 18.dp
private const val LightShadowAlpha = 0.18f
private const val DarkShadowAlpha = 0.3f
private const val WaveFadeInFraction = 0.1f
private const val WaveDurationMillis = 640

@Immutable
private data class WavePulse(val serial: Int, val position: Offset)

/** Holds pointer coordinates until the status area's actual click accepts or cancels the gesture. */
@Stable
internal class MainWaveShadowState {
    private var pointerPosition = Offset.Unspecified
    private var pulse by mutableStateOf(WavePulse(0, Offset.Unspecified))

    val trigger: Int get() = pulse.serial
    val position: Offset get() = pulse.position

    fun capturePointer(position: Offset) {
        pointerPosition = position
    }

    fun clearPointer() {
        pointerPosition = Offset.Unspecified
    }

    /** Keyboard and accessibility clicks have no pointer and use the status area's center. */
    fun emit() {
        pulse = WavePulse(pulse.serial + 1, pointerPosition)
        clearPointer()
    }
}

/**
 * Sends a soft shadow wave out from the actual click, behind the status text and inside its hit target.
 * Apply before the status content padding. The local rectangular clip excludes the sibling FAB;
 * the parent Surface additionally clips the outer rounded corners of the glass panel.
 * A radial gradient feathers both edges of the shadow band, which expands and fades once per click.
 * Pointer observation never consumes events, so clickable keeps ownership of taps and semantics.
 */
@Composable
internal fun Modifier.mainWaveShadow(
    state: MainWaveShadowState,
    color: Color,
    darkTheme: Boolean,
    enabled: Boolean
): Modifier {
    val progress = remember { Animatable(1f) }
    val trigger = state.trigger
    LaunchedEffect(state, trigger) {
        if (trigger == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(WaveDurationMillis, easing = LinearEasing))
    }

    return this
        .pointerInput(state, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                try {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    state.capturePointer(down.position)
                    var event = awaitPointerEvent(PointerEventPass.Final)
                    while (event.changes.any { it.pressed }) {
                        event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            state.capturePointer(it.position)
                        }
                        // clickable calls onClick during Main; clear coordinates only after Final.
                        event = awaitPointerEvent(PointerEventPass.Final)
                    }
                } finally {
                    state.clearPointer()
                }
            }
        }
        .graphicsLayer()
        .drawWithCache {
            val bandWidth = WaveBandWidth.toPx()
            val startRadius = bandWidth / 2
            val maxAlpha = if (darkTheme) DarkShadowAlpha else LightShadowAlpha
            val transparent = color.copy(alpha = 0f)

            onDrawBehind {
                val phase = progress.value.coerceIn(0f, 1f)
                if (phase >= 1f || size.width <= 0f || size.height <= 0f) return@onDrawBehind
                val clickPosition = state.position
                val origin = if (clickPosition.isSpecified) {
                    Offset(
                        clickPosition.x.coerceIn(0f, size.width),
                        clickPosition.y.coerceIn(0f, size.height)
                    )
                } else {
                    center
                }
                val farX = max(origin.x, size.width - origin.x)
                val farY = max(origin.y, size.height - origin.y)
                val endRadius = sqrt(farX * farX + farY * farY) + bandWidth
                val radius = startRadius + (endRadius - startRadius) * phase
                val fadeIn = (phase / WaveFadeInFraction).coerceIn(0f, 1f)
                val alpha = maxAlpha * fadeIn * (1f - phase)
                if (alpha <= 0f) return@onDrawBehind
                val width = bandWidth.coerceAtMost(radius)
                val innerStop = (radius - width) / radius
                val peakStop = (radius - width / 2) / radius
                val shadow = Brush.radialGradient(
                    innerStop to transparent,
                    peakStop to color.copy(alpha = alpha),
                    1f to transparent,
                    center = origin,
                    radius = radius
                )
                clipRect {
                    drawCircle(brush = shadow, radius = radius, center = origin)
                }
            }
        }
}
