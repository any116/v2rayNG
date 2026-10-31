package com.v2ray.ang.ui.main

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.v2ray.ang.R
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.LocalPredictiveBackProgress
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DrawerMaxWidth = 360.dp
private val DrawerEdgeWidth = 24.dp
private val DrawerFlingVelocity = 125.dp
// This is a full-page frame, deliberately rounder than the small components inside it.
private val HomeCornerRadius = 32.dp
private const val HomeScaleReduction = 0.04f

/**
 * Keeps BaseScreen mounted at full size. Only the foreground layer reads frame-by-frame progress;
 * focus, semantics and the dismiss hit target observe a derived open/closed boundary instead.
 */
@Composable
internal fun MainPushDrawer(
    state: MainPushDrawerState,
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val visible by remember(state) { derivedStateOf { state.isVisible } }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isResumed = lifecycleState == Lifecycle.State.RESUMED
    val drawerInteractive = visible && isResumed
    val homeInteractive = !visible && isResumed
    val inputMode = LocalInputModeManager.current.inputMode
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val maxWidth = with(density) { DrawerMaxWidth.toPx() }
    val edgeWidth = with(density) { DrawerEdgeWidth.toPx() }
    val velocityThreshold = with(density) { DrawerFlingVelocity.toPx() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val drawerFocus = remember { FocusRequester() }
    val homeFocus = remember { FocusRequester() }
    val close = remember(state, scope) { { scope.launch { state.close() }; Unit } }
    val closeLabel = stringResource(R.string.navigation_drawer_close)
    val paneLabel = stringResource(R.string.navigation_drawer_title)
    var wasVisible by remember { mutableStateOf(false) }

    // Requesting keyboard focus after a touch leaves Material's focus state layer on menu buttons.
    // Also leave focus to the new destination while this retained home is below RESUMED.
    LaunchedEffect(visible, isResumed, inputMode) {
        when (mainDrawerFocusAction(visible, wasVisible, isResumed, inputMode == InputMode.Keyboard)) {
            MainDrawerFocusAction.NONE -> Unit
            MainDrawerFocusAction.CLEAR -> focusManager.clearFocus(force = true)
            MainDrawerFocusAction.DRAWER -> {
                focusManager.clearFocus(force = true)
                drawerFocus.requestFocus()
            }
            MainDrawerFocusAction.HOME -> {
                focusManager.clearFocus(force = true)
                homeFocus.requestFocus()
            }
        }
        if (isResumed) {
            if (visible) keyboard?.hide()
            wasVisible = visible
        }
    }
    MainDrawerBackHandler(state, isResumed)

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (isResumed) Modifier else Modifier.clearAndSetSemantics { })
            .mainDrawerGestures(
                state, scope, isRtl, edgeWidth, maxWidth, velocityThreshold,
                enabled = gesturesEnabled && isResumed
            )
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val width = mainDrawerWidth(constraints.maxWidth.toFloat(), maxWidth).roundToInt()
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(width, placeable.height) { placeable.placeRelative(0, 0) }
                }
                .focusRequester(drawerFocus)
                .focusProperties { onEnter = { if (!drawerInteractive) cancelFocusChange() } }
                .focusGroup()
                .focusProperties { if (!drawerInteractive) canFocus = false }
                .then(
                    if (drawerInteractive) Modifier.semantics {
                        paneTitle = paneLabel
                        dismiss { close(); true }
                    } else Modifier.clearAndSetSemantics { }
                )
        ) {
            drawerContent()
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val phase = state.progress
                    translationX = mainDrawerWidth(size.width, maxWidth) * phase * if (isRtl) -1f else 1f
                    transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0.5f)
                    scaleX = 1f - HomeScaleReduction * phase
                    scaleY = scaleX
                    shape = RoundedCornerShape(HomeCornerRadius * phase)
                    clip = phase > 0f
                }
                // Like a non-clickable Surface: gaps in home still occlude hidden menu hit targets.
                .pointerInput(Unit) { }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(homeFocus)
                    .focusProperties { onEnter = { if (!homeInteractive) cancelFocusChange() } }
                    .focusRestorer()
                    .focusGroup()
                    .focusProperties { if (!homeInteractive) canFocus = false }
                    .then(if (homeInteractive) Modifier else Modifier.clearAndSetSemantics { })
            ) {
                content()
            }
            if (visible) {
                // The foreground remains visible, but cannot select rows, toggle VPN or page groups.
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            enabled = isResumed,
                            onClickLabel = closeLabel,
                            onClick = close
                        )
                        .semantics { this.contentDescription = closeLabel }
                )
            }
        }
    }
}

@Composable
private fun MainDrawerBackHandler(state: MainPushDrawerState, isResumed: Boolean) {
    val scope = rememberCoroutineScope()
    val visible by remember(state) { derivedStateOf { state.isVisible } }
    val enabled = visible && isResumed
    val back = LocalPredictiveBackProgress.current
    val predictive = back?.enabled == true
    BackHandler(enabled = enabled && !predictive) { scope.launch { state.close() } }
    PredictiveBackHandler(enabled = enabled && predictive) { events ->
        back?.onBackStarted()
        try {
            state.closePredictively(events.map { it.progress }) { restoreOpen ->
                scope.launch { state.restoreAfterCancelledBack(restoreOpen) }
            }
        } finally {
            // Drawer back is local work, not a destination pop; never suppress home indefinitely.
            back?.onBackFinished()
        }
    }
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainPushDrawerOpenPreview() = MainPushDrawerPreview(open = true)

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainPushDrawerClosedPreview() = MainPushDrawerPreview(open = false)

@Composable
private fun MainPushDrawerPreview(open: Boolean) = AppTheme {
    val state = rememberMainPushDrawerState(initialOpen = open)
    MainPushDrawer(state, drawerContent = { MainDrawerContent(onAction = {}) }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(HomeCornerRadius)) {
            Text(stringResource(R.string.title_server), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.connection_not_connected))
        }
    }
}
