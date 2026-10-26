package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.components.LocalDarkTheme
import kotlinx.coroutines.launch

private val BarMinHeight = 72.dp
private val BarContentStartPadding = 16.dp
private val BarContentEndPadding = 8.dp
private val BarItemSpacing = 8.dp
private val StatusVerticalPadding = 12.dp
private val FabVerticalPadding = 8.dp
private val BarBorderWidth = 1.dp
private const val BarFallbackAlpha = 0.95f
private const val BarBorderAlpha = 0.35f
private val FabSize = 56.dp
private val FabIconSize = 24.dp
// Displacement magnitudes only — the timing now comes from the theme's motion scheme.
private const val FabIconMinScale = 0.85f
private const val FabBounceStartScale = 0.96f
private const val FabIconWaveStartScale = 0.92f
private const val FabIconWaveRotationDegrees = 4f

@Composable
internal fun MainBottomBar(
    statusText: String,
    isRunning: Boolean,
    isTesting: Boolean,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
    suppressAnimations: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraLarge

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        shape = shape,
        // Keep the Android 12-and-below fallback for every API level; no backdrop blur is used.
        color = colors.primaryContainer.copy(alpha = BarFallbackAlpha),
        contentColor = colors.onPrimaryContainer,
        border = BorderStroke(
            width = BarBorderWidth,
            color = colors.outlineVariant.copy(alpha = BarBorderAlpha)
        ),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BarMinHeight)
                .height(IntrinsicSize.Min)
                .padding(end = BarContentEndPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BarItemSpacing)
        ) {
            StatusSection(
                statusText = statusText,
                enabled = isRunning || isTesting,
                isTesting = isTesting,
                shape = shape,
                suppressAnimations = suppressAnimations,
                onClick = { onAction(MainAction.StatusBarClick) }
            )
            ServiceFab(
                isRunning = isRunning,
                suppressAnimations = suppressAnimations,
                onClick = { onAction(MainAction.ToggleService) }
            )
        }
    }
}

@Composable
private fun RowScope.StatusSection(
    statusText: String,
    enabled: Boolean,
    isTesting: Boolean,
    shape: Shape,
    suppressAnimations: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val darkTheme = LocalDarkTheme.current
    val interaction = remember { MutableInteractionSource() }
    val wave = remember { MainWaveShadowState() }
    val focused by interaction.collectIsFocusedAsState()
    val actionLabel = stringResource(
        if (isTesting) R.string.acc_cancel_connection_test else R.string.acc_check_connection
    )

    Box(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .mainWaveShadow(
                state = wave,
                color = colors.scrim,
                darkTheme = darkTheme,
                enabled = enabled,
                suppressAnimations = suppressAnimations
            )
            .border(
                width = BarBorderWidth,
                color = if (focused && enabled) colors.outline else Color.Transparent,
                shape = shape
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = {
                    wave.emit()
                    onClick()
                }
            )
            .semantics { contentDescription = statusText }
            // Keep the inset inside the hit target so the whole outer start arc stays clickable.
            .padding(
                start = BarContentStartPadding,
                top = StatusVerticalPadding,
                bottom = StatusVerticalPadding
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = statusText,
            // The parent announces the full, untruncated status once.
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceFab(
    isRunning: Boolean,
    onClick: () -> Unit,
    suppressAnimations: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bounce = remember { Animatable(1f) }
    var hasAppeared by remember { mutableStateOf(false) }
    // Scaling the whole FAB is spatial motion; colors and alpha are effects and must not overshoot.
    val motion = MaterialTheme.motionScheme
    val fabSpatialSpec = motion.fastSpatialSpec<Float>()
    val fabColorSpec = motion.fastEffectsSpec<Color>()
    val fabFocusRingSpec = motion.fastEffectsSpec<Float>()

    LaunchedEffect(isRunning) {
        // Do not animate the initial layout; only confirmed service state changes get feedback.
        if (!hasAppeared) {
            hasAppeared = true
            return@LaunchedEffect
        }
        bounce.snapTo(FabBounceStartScale)
        bounce.animateTo(1f, fabSpatialSpec)
    }

    // The transition owns both colors so they cannot drift if the timing changes later.
    val transition = updateTransition(
        targetState = isRunning,
        label = "ServiceFabState"
    )
    val containerColor by transition.animateColor(
        transitionSpec = { fabColorSpec },
        label = "FabContainerColor"
    ) { running -> if (running) colors.secondary else colors.surfaceContainerHighest }
    val contentColor by transition.animateColor(
        transitionSpec = { fabColorSpec },
        label = "FabContentColor"
    ) { running -> if (running) colors.onSecondary else colors.onSurface }
    val focusRingAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = fabFocusRingSpec,
        label = "FabFocusRingAlpha"
    )

    // Material FAB has its own ripple; disabling only the status indication isn't enough.
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier
                .padding(vertical = FabVerticalPadding)
                .size(FabSize)
                .border(
                    width = BarBorderWidth,
                    color = contentColor.copy(alpha = focusRingAlpha),
                    shape = FloatingActionButtonDefaults.shape
                )
                .graphicsLayer {
                    val value = if (suppressAnimations) 1f else bounce.value
                    scaleX = value
                    scaleY = value
                },
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp
            ),
            interactionSource = interaction
        ) {
            ServiceIcon(
                isRunning = isRunning,
                suppressAnimations = suppressAnimations,
                modifier = Modifier.size(FabIconSize)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceIcon(
    isRunning: Boolean,
    suppressAnimations: Boolean,
    modifier: Modifier = Modifier
) {
    // Resolve both vectors up front so resource parsing cannot happen during the swap animation.
    val playPainter = painterResource(R.drawable.ic_play_24dp)
    val stopPainter = painterResource(R.drawable.ic_stop_24dp)
    var displayedRunning by remember { mutableStateOf(isRunning) }

    val motion = MaterialTheme.motionScheme
    val iconAlphaSpec = motion.fastEffectsSpec<Float>()
    val iconSpatialSpec = motion.fastSpatialSpec<Float>()

    // Alpha and scale need separate animatables: alpha is effects motion (no overshoot),
    // scale and rotation are spatial motion (springs are allowed to overshoot).
    val alphaProgress = remember { Animatable(1f) }
    val scaleProgress = remember { Animatable(1f) }
    val wave = remember { Animatable(1f) }

    LaunchedEffect(isRunning) {
        if (displayedRunning == isRunning) {
            return@LaunchedEffect
        }

        // Fade the old icon out before swapping the painter so the two icons never overlap.
        val fadeOut = launch { alphaProgress.animateTo(0f, iconAlphaSpec) }
        launch { scaleProgress.animateTo(0f, iconSpatialSpec) }
        fadeOut.join()

        displayedRunning = isRunning
        wave.snapTo(0f)
        // Run the damped wave beside the fade-in so the new icon settles as it appears.
        launch { wave.animateTo(1f, iconSpatialSpec) }

        // Springs retarget from their current velocity, so an interruption mid-fade resolves
        // smoothly without any manual duration scaling.
        launch { alphaProgress.animateTo(1f, iconAlphaSpec) }
        scaleProgress.animateTo(1f, iconSpatialSpec)
    }

    Icon(
        painter = if (displayedRunning) stopPainter else playPainter,
        // Accessibility follows the real service state immediately; the painter intentionally
        // lags while the outgoing icon finishes its fade.
        contentDescription = stringResource(if (isRunning) R.string.acc_stop else R.string.acc_start),
        modifier = modifier
            .graphicsLayer {
                if (suppressAnimations) {
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                    rotationZ = 0f
                } else {
                    val fadeScale = FabIconMinScale + (1f - FabIconMinScale) * scaleProgress.value
                    val waveValue = wave.value
                    val waveScale = FabIconWaveStartScale + (1f - FabIconWaveStartScale) * waveValue
                    val scale = fadeScale * waveScale
                    alpha = alphaProgress.value
                    scaleX = scale
                    scaleY = scale
                    rotationZ = (1f - waveValue) * FabIconWaveRotationDegrees
                }
            }
    )
}
