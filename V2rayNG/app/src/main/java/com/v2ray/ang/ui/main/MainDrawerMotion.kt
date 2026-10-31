package com.v2ray.ang.ui.main

import kotlin.math.abs

private const val DrawerWidthFraction = 0.75f
private const val DrawerPositionThreshold = 0.5f

/** Uses window constraints, not display metrics, so split-screen and rotation preserve a home preview. */
internal fun mainDrawerWidth(windowWidth: Float, maxWidth: Float): Float =
    (windowWidth * DrawerWidthFraction).coerceIn(0f, maxWidth.coerceAtLeast(0f))

/** Velocity is in logical-start coordinates: positive opens in either LTR or RTL. */
internal fun mainDrawerTargetOpen(progress: Float, velocity: Float, velocityThreshold: Float): Boolean = when {
    progress <= 0f -> false
    progress >= 1f -> true
    abs(velocity) >= velocityThreshold -> velocity > 0f
    else -> progress >= DrawerPositionThreshold
}

internal fun isMainDrawerEdgeStart(x: Float, width: Float, edgeWidth: Float, isRtl: Boolean): Boolean {
    if (width <= 0f || x < 0f || x > width) return false
    return (if (isRtl) width - x else x) <= edgeWidth
}

internal fun shouldStartMainDrawerDrag(dx: Float, dy: Float, slop: Float, alreadyVisible: Boolean): Boolean =
    abs(dx) > slop && abs(dx) > abs(dy) && (alreadyVisible || dx > 0f)

/** UI-scope gate: do not overlap drawer closing with destination transitions or queue duplicate taps. */
internal class MainDrawerActionGate {
    var isRunning: Boolean = false
        private set

    suspend fun run(close: suspend () -> Unit, action: () -> Unit) {
        if (isRunning) return
        isRunning = true
        try {
            close()
            action()
        } finally {
            isRunning = false
        }
    }
}
