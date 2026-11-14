package com.v2ray.ang.ui.main

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
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
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.components.AppDivider
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.LocalDarkTheme
import com.v2ray.ang.ui.components.LocalPredictiveBackProgress
import com.v2ray.ang.ui.components.verticalScrollbar
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val DrawerHeaderHeight = 180.dp
private val DrawerHeaderPadding = 16.dp
private val LogoSize = 120.dp

private val DrawerMaxWidth = 360.dp
private val DrawerEdgeWidth = 24.dp
private val DrawerFlingVelocity = 125.dp
// This is a full-page frame, deliberately rounder than the small components inside it.
private val HomeCornerRadius = 32.dp
private const val HomeScaleReduction = 0.08f

@Immutable
private data class DrawerItem(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val action: MainAction
)

private val drawerMenus = listOf(
    listOf(
        DrawerItem(R.drawable.ic_subscriptions_24dp, R.string.title_sub_setting, MainAction.Navigate(AppRoute.SubSetting)),
        DrawerItem(R.drawable.ic_per_apps_24dp, R.string.per_app_proxy_settings, MainAction.Navigate(AppRoute.PerAppProxy)),
        DrawerItem(R.drawable.ic_routing_24dp, R.string.routing_settings_title, MainAction.Navigate(AppRoute.RoutingSetting)),
        DrawerItem(R.drawable.ic_file_24dp, R.string.title_user_asset_setting, MainAction.Navigate(AppRoute.UserAsset)),
        DrawerItem(R.drawable.ic_settings_24dp, R.string.title_settings, MainAction.Navigate(AppRoute.Settings))
    ),
    listOf(
        DrawerItem(R.drawable.ic_promotion_24dp, R.string.title_pref_promotion, MainAction.OpenPromotion),
        DrawerItem(R.drawable.ic_logcat_24dp, R.string.title_logcat, MainAction.Navigate(AppRoute.Logcat)),
        DrawerItem(R.drawable.ic_check_update_24dp, R.string.update_check_for_update, MainAction.Navigate(AppRoute.CheckUpdate)),
        DrawerItem(R.drawable.ic_restore_24dp, R.string.title_configuration_backup_restore, MainAction.Navigate(AppRoute.Backup)),
        DrawerItem(R.drawable.ic_about_24dp, R.string.title_about, MainAction.Navigate(AppRoute.About))
    )
)

@Composable
fun MainDrawerContent(
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Width belongs to MainPushDrawer. Applying a fraction here would shrink it a second time.
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .verticalScrollbar(scrollState)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DrawerHeaderHeight)
                    .padding(DrawerHeaderPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val isDarkTheme = LocalDarkTheme.current
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(LogoSize),
                    colorFilter = if (isDarkTheme) {
                        ColorFilter.tint(MaterialTheme.colorScheme.onSurface, BlendMode.SrcIn)
                    } else {
                        null
                    }
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            drawerMenus.forEachIndexed { index, menu ->
                if (index > 0) AppDivider()
                menu.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(item.labelRes)) },
                        selected = false,
                        onClick = { onAction(item.action) },
                        icon = { Icon(painterResource(item.iconRes), contentDescription = null) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    }
}

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
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
                    val homeScale = 1f - HomeScaleReduction * phase
                    translationX = mainDrawerWidth(size.width, maxWidth) * phase * if (isRtl) -1f else 1f
                    transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0.5f)
                    scaleX = homeScale
                    scaleY = homeScale
                    // Compensate for the layer scale so the visible corner radius stays stable.
                    shape = RoundedCornerShape(HomeCornerRadius * phase / homeScale)
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
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(HomeCornerRadius)) {
            Text(stringResource(R.string.title_server), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.connection_not_connected))
        }
    }
}

/**
 * An ancestor observer, not an invisible edge overlay: vertical scrolling and ordinary Pager swipes
 * keep their original hit targets. Only an inward, horizontal edge drag claims the Initial pass;
 * after opening, horizontal drags anywhere in the drawer frame can close it. No system-gesture
 * exclusion is requested, so Android retains ownership of its back edge.
 */
private fun Modifier.mainDrawerGestures(
    state: MainPushDrawerState,
    scope: CoroutineScope,
    isRtl: Boolean,
    edgeWidth: Float,
    maxDrawerWidth: Float,
    velocityThreshold: Float,
    enabled: Boolean
): Modifier = pointerInput(state, isRtl, edgeWidth, maxDrawerWidth, velocityThreshold, enabled) {
    if (!enabled) return@pointerInput
    val direction = if (isRtl) -1f else 1f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!state.canStartGesture()) return@awaitEachGesture
        val visibleOnDown = state.isVisible
        if (!visibleOnDown && !isMainDrawerEdgeStart(down.position.x, size.width.toFloat(), edgeWidth, isRtl)) {
            return@awaitEachGesture
        }
        val width = mainDrawerWidth(size.width.toFloat(), maxDrawerWidth)
        if (width <= 0f) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var distance = Offset.Zero
        var firstDelta: Float
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed || !state.canStartGesture()) return@awaitEachGesture
            tracker.addPosition(change.uptimeMillis, change.position)
            distance += change.positionChange()
            val dx = distance.x * direction
            val slop = viewConfiguration.touchSlop
            if (shouldStartMainDrawerDrag(dx, distance.y, slop, visibleOnDown)) {
                firstDelta = dx - sign(dx) * slop
                change.consume()
                break
            }
            // Yield the whole sequence, not just this event, to vertical scroll or an outward swipe.
            if (abs(distance.y) > slop || abs(dx) > slop) return@awaitEachGesture
            val final = awaitPointerEvent(PointerEventPass.Final)
            if (final.changes.any { it.id == down.id && it.isConsumed }) return@awaitEachGesture
        }

        // Pointer scopes are restricted suspending scopes. The UI-owned worker holds Foundation's
        // drag mutex while deltas arrive; closing the channel also handles platform cancellation.
        val deltas = Channel<Float>(Channel.UNLIMITED)
        var velocity = 0f
        var cancelled = true
        val drag = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                state.drag {
                    for (delta in deltas) dragBy(delta / width)
                }
                state.settle(velocity, width, velocityThreshold, cancelled)
            } finally {
                deltas.cancel()
            }
        }
        try {
            deltas.trySend(firstDelta)
            while (drag.isActive) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    velocity = tracker.calculateVelocity().x * direction
                    cancelled = false
                    change.consume()
                    break
                }
                if (deltas.trySend(change.positionChange().x * direction).isFailure) break
                change.consume()
            }
        } finally {
            deltas.close()
        }
    }
}
