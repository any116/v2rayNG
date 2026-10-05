package com.v2ray.ang.ui.components

import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.nonInteractiveScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val ThumbAlpha = 0.7f
private const val ThumbMaxLengthFraction = 0.5f
private val FastScrollTouchTarget = 48.dp

@Immutable
data class ScrollbarConfig(
    val thickness: Dp = 4.dp,
    val minThumbSize: Dp = 24.dp,
    val thumbColor: Color = Color.Unspecified,
    val trackColor: Color = Color.Transparent,
    val padding: Dp = 2.dp,
    val cornerRadius: Dp = 2.dp,
    val fadeOutDurationMs: Int = 1500,
    val fadeAnimDurationMs: Int = 300
) {
    companion object {
        val Default = ScrollbarConfig()
    }
}

@Composable
private fun Modifier.m3Scrollbar(
    state: ScrollIndicatorState?,
    orientation: Orientation,
    config: ScrollbarConfig,
    scrollableState: ScrollableState
): Modifier {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    if (!isResumed) return this

    val resolvedState = state ?: return this
    val thumbColor = if (config.thumbColor == Color.Unspecified) {
        MaterialTheme.colorScheme.secondary.copy(alpha = ThumbAlpha)
    } else {
        config.thumbColor
    }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    return this.then(
        Modifier.nonInteractiveScrollbar(
            state = resolvedState,
            orientation = orientation,
            thumbColor = thumbColor,
            trackColor = config.trackColor,
            thickness = config.thickness,
            thumbMinLength = config.minThumbSize,
            thumbMaxLengthFraction = ThumbMaxLengthFraction,
            isFadeEnabled = true,
            fadeDelayMillis = config.fadeOutDurationMs,
            fadeDurationMillis = config.fadeAnimDurationMs,
            mainAxisTrackInset = config.padding,
            crossAxisTrackInset = config.padding
        )
    ).then(
        Modifier.fastScrollbarInput(
            state = resolvedState,
            orientation = orientation,
            config = config,
            scrollableState = scrollableState,
            density = density,
            layoutDirection = layoutDirection
        )
    )
}

private data class ScrollbarGeometry(
    val trackStart: Float,
    val thumbLength: Float,
    val thumbStart: Float,
    val maxThumbOffset: Float,
    val maxScrollOffset: Float
)

private fun calculateScrollbarGeometry(
    state: ScrollIndicatorState,
    orientation: Orientation,
    config: ScrollbarConfig,
    density: Density,
    layoutDirection: LayoutDirection,
    mainAxisSize: Int
): ScrollbarGeometry? {
    val trackInset = with(density) { config.padding.toPx() }
    val trackLength = mainAxisSize - trackInset * 2
    val contentSize = state.contentSize.toFloat()
    val viewportSize = state.viewportSize.toFloat()
    val minThumbLength = with(density) { config.minThumbSize.toPx() }

    if (
        contentSize <= viewportSize ||
            viewportSize <= 0f ||
            trackLength < minThumbLength ||
            trackLength <= 0f
    ) {
        return null
    }

    val thumbLength =
        (trackLength * viewportSize / contentSize)
            .coerceIn(minThumbLength, trackLength * ThumbMaxLengthFraction)
    val maxThumbOffset = (trackLength - thumbLength).coerceAtLeast(0f)
    val maxScrollOffset = (contentSize - viewportSize).coerceAtLeast(0f)
    val scrollOffset = state.scrollOffset.toFloat().coerceIn(0f, maxScrollOffset)
    val logicalThumbOffset =
        if (maxScrollOffset > 0f) {
            scrollOffset / maxScrollOffset * maxThumbOffset
        } else {
            0f
        }
    val thumbStart =
        if (orientation == Orientation.Horizontal && layoutDirection == LayoutDirection.Rtl) {
            trackInset + maxThumbOffset - logicalThumbOffset
        } else {
            trackInset + logicalThumbOffset
        }

    return ScrollbarGeometry(
        trackStart = trackInset,
        thumbLength = thumbLength,
        thumbStart = thumbStart,
        maxThumbOffset = maxThumbOffset,
        maxScrollOffset = maxScrollOffset
    )
}

private fun Modifier.fastScrollbarInput(
    state: ScrollIndicatorState,
    orientation: Orientation,
    config: ScrollbarConfig,
    scrollableState: ScrollableState,
    density: Density,
    layoutDirection: LayoutDirection
): Modifier = pointerInput(state, scrollableState, orientation, config, density, layoutDirection) {
    coroutineScope {
        var scrollJob: Job? = null
        detectFastScrollGestures(state, orientation, config, density, layoutDirection) { targetOffset ->
            // Keep scrollBy outside awaitEachGesture's restricted scope; newer targets replace old ones.
            scrollJob?.cancel()
            scrollJob = launch {
                val scrollDelta = targetOffset - state.scrollOffset
                if (abs(scrollDelta) > 0.5f) {
                    scrollableState.scrollBy(scrollDelta)
                }
            }
        }
    }
}

private suspend fun PointerInputScope.detectFastScrollGestures(
    state: ScrollIndicatorState,
    orientation: Orientation,
    config: ScrollbarConfig,
    density: Density,
    layoutDirection: LayoutDirection,
    onScrollTarget: (Float) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val mainAxisSize = if (orientation == Orientation.Vertical) size.height else size.width
        val crossAxisSize = if (orientation == Orientation.Vertical) size.width else size.height
        val geometry =
            calculateScrollbarGeometry(
                state = state,
                orientation = orientation,
                config = config,
                density = density,
                layoutDirection = layoutDirection,
                mainAxisSize = mainAxisSize
            )
        val touchTarget =
            minOf(
                crossAxisSize.toFloat(),
                with(density) { FastScrollTouchTarget.toPx() }
            )

        if (
            geometry == null ||
                !isInScrollbarTouchTarget(
                    position = down.position,
                    orientation = orientation,
                    layoutDirection = layoutDirection,
                    width = size.width,
                    height = size.height,
                    touchTarget = touchTarget
                )
        ) {
            return@awaitEachGesture
        }

        val downPosition = mainAxisPosition(down.position, orientation)
        val isOnThumb =
            downPosition >= geometry.thumbStart &&
                downPosition <= geometry.thumbStart + geometry.thumbLength
        val grabOffset =
            if (isOnThumb) {
                downPosition - geometry.thumbStart
            } else {
                geometry.thumbLength / 2f
            }

        val pointerId = down.id
        var dragging = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                if (dragging) change.consume()
                break
            }

            if (!dragging) {
                if (change.isConsumed) break
                val mainAxisDelta = mainAxisPosition(change.position, orientation) - downPosition
                if (abs(mainAxisDelta) < viewConfiguration.touchSlop) continue
                dragging = true
            }

            val currentGeometry =
                calculateScrollbarGeometry(
                    state = state,
                    orientation = orientation,
                    config = config,
                    density = density,
                    layoutDirection = layoutDirection,
                    mainAxisSize = mainAxisSize
                ) ?: break
            change.consume()
            onScrollTarget(
                calculateScrollbarTargetOffset(
                    position = mainAxisPosition(change.position, orientation),
                    grabOffset = grabOffset,
                    geometry = currentGeometry,
                    orientation = orientation,
                    layoutDirection = layoutDirection
                )
            )
        }
    }
}

private fun isInScrollbarTouchTarget(
    position: Offset,
    orientation: Orientation,
    layoutDirection: LayoutDirection,
    width: Int,
    height: Int,
    touchTarget: Float
): Boolean =
    when (orientation) {
        Orientation.Vertical ->
            if (layoutDirection == LayoutDirection.Ltr) {
                position.x >= width - touchTarget
            } else {
                position.x <= touchTarget
            }

        Orientation.Horizontal -> position.y >= height - touchTarget
    }

private fun mainAxisPosition(position: Offset, orientation: Orientation): Float =
    if (orientation == Orientation.Vertical) position.y else position.x

private fun calculateScrollbarTargetOffset(
    position: Float,
    grabOffset: Float,
    geometry: ScrollbarGeometry,
    orientation: Orientation,
    layoutDirection: LayoutDirection
): Float {
    val visualThumbStart = position - grabOffset
    val logicalThumbOffset =
        if (orientation == Orientation.Horizontal && layoutDirection == LayoutDirection.Rtl) {
            geometry.trackStart + geometry.maxThumbOffset - visualThumbStart
        } else {
            visualThumbStart - geometry.trackStart
        }
    return if (geometry.maxThumbOffset > 0f) {
        logicalThumbOffset
            .coerceIn(0f, geometry.maxThumbOffset)
            .div(geometry.maxThumbOffset) * geometry.maxScrollOffset
    } else {
        0f
    }
}

@Composable
fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    config: ScrollbarConfig = ScrollbarConfig.Default
): Modifier =
    m3Scrollbar(
        state = scrollState.scrollIndicatorState,
        orientation = Orientation.Vertical,
        config = config,
        scrollableState = scrollState
    )

@Composable
fun Modifier.horizontalScrollbar(
    scrollState: ScrollState,
    config: ScrollbarConfig = ScrollbarConfig.Default
): Modifier =
    m3Scrollbar(
        state = scrollState.scrollIndicatorState,
        orientation = Orientation.Horizontal,
        config = config,
        scrollableState = scrollState
    )

@Composable
fun Modifier.verticalScrollbar(
    lazyListState: LazyListState,
    config: ScrollbarConfig = ScrollbarConfig.Default
): Modifier =
    m3Scrollbar(
        state = lazyListState.scrollIndicatorState,
        orientation = Orientation.Vertical,
        config = config,
        scrollableState = lazyListState
    )

@Composable
fun Modifier.verticalScrollbar(
    lazyGridState: LazyGridState,
    config: ScrollbarConfig = ScrollbarConfig.Default
): Modifier =
    m3Scrollbar(
        state = lazyGridState.scrollIndicatorState,
        orientation = Orientation.Vertical,
        config = config,
        scrollableState = lazyGridState
    )
