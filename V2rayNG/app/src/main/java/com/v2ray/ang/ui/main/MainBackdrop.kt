package com.v2ray.ang.ui.main

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

private val BackdropBlurRadius = 20.dp
private const val BlurSampleMargin = 3

/** Shares a display list, never a bitmap, between the list and its floating bottom bar. */
@Stable
internal class MainBackdrop(val contentLayer: GraphicsLayer?) {
    var sourcePosition by mutableStateOf(Offset.Unspecified)
    var contentVersion by mutableIntStateOf(0)
        private set

    fun onContentRecorded() {
        // Only the effect observes this counter. Observing it in the source's draw scope would
        // invalidate the source after every recording and create a continuous redraw loop.
        Snapshot.withoutReadObservation { contentVersion++ }
    }
}

@Composable
internal fun rememberMainBackdrop(): MainBackdrop {
    val view = LocalView.current
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && view.isHardwareAccelerated
    val layer = if (supportsBlur) rememberGraphicsLayer() else null
    return remember(layer) { MainBackdrop(layer) }
}

/** Captures only the list; capturing the bottom bar too would recursively draw its own backdrop. */
internal fun Modifier.mainBackdropSource(backdrop: MainBackdrop): Modifier {
    val layer = backdrop.contentLayer ?: return this
    return this
        // Isolate this source so repainting its sibling effect doesn't record the source again.
        .graphicsLayer()
        .onGloballyPositioned { backdrop.sourcePosition = it.positionInRoot() }
        .drawWithContent {
            if (size.width > 0 && size.height > 0) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
                backdrop.onContentRecorded()
            } else {
                drawContent()
            }
        }
}

/** Blurs a padded crop of the list, keeping the text and controls outside the blur layer. */
@Composable
internal fun Modifier.mainBackdropEffect(
    backdrop: MainBackdrop,
    backgroundColor: Color,
    tintColor: Color,
    fallbackColor: Color
): Modifier {
    val contentLayer = backdrop.contentLayer
    val blurLayer = if (contentLayer != null) rememberGraphicsLayer() else null
    var effectPosition by remember { mutableStateOf(Offset.Unspecified) }

    return this
        .onGloballyPositioned { effectPosition = it.positionInRoot() }
        .drawWithCache {
            val radius = BackdropBlurRadius.toPx()
            val margin = ceil(radius * BlurSampleMargin).toInt()
            val cropSize = IntSize(
                ceil(size.width).toInt() + margin * 2,
                ceil(size.height).toInt() + margin * 2
            )
            blurLayer?.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)

            onDrawBehind {
                if (contentLayer != null && blurLayer != null && backdrop.contentVersion > 0 &&
                    backdrop.sourcePosition.isSpecified && effectPosition.isSpecified
                ) {
                    val offset = effectPosition - backdrop.sourcePosition
                    blurLayer.record(size = cropSize) {
                        drawRect(backgroundColor)
                        translate(margin - offset.x, margin - offset.y) {
                            drawLayer(contentLayer)
                        }
                    }
                    translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(blurLayer) }
                    drawRect(tintColor)
                } else {
                    drawRect(fallbackColor)
                }
            }
        }
}
