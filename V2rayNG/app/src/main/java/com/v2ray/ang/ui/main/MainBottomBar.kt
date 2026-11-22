package com.v2ray.ang.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

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

@Composable
internal fun MainBottomBar(
    statusText: String,
    isRunning: Boolean,
    isTesting: Boolean,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraLarge
    val interaction = remember { MutableInteractionSource() }
    val barRipple = rememberBarRippleSource(interaction)
    val enabled = isRunning || isTesting
    val actionLabel = stringResource(
        if (isTesting) R.string.acc_cancel_connection_test else R.string.acc_check_connection
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        shape = shape,
        color = colors.primaryContainer.copy(alpha = BarFallbackAlpha),
        contentColor = colors.onPrimaryContainer,
        border = BorderStroke(
            width = BarBorderWidth,
            color = colors.outlineVariant.copy(alpha = BarBorderAlpha)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        // FAB handles its own presses; every other part of the panel shares this full-size target.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = actionLabel,
                    role = Role.Button,
                    onClick = { onAction(MainAction.StatusBarClick) }
                )
                .indication(barRipple, ripple(focusRingShape = shape))
                .semantics { contentDescription = statusText }
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
                StatusSection(statusText = statusText)
                ServiceFab(
                    isRunning = isRunning,
                    onClick = { onAction(MainAction.ToggleService) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.StatusSection(
    statusText: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
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

@Composable
private fun ServiceFab(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    // Reuse the panel backdrop without compositing its translucent color a second time.
    val containerColor = if (isRunning) colors.secondary else Color.Transparent
    val contentColor = if (isRunning) colors.onSecondary else colors.onPrimaryContainer
    val elevation = if (isRunning) {
        FloatingActionButtonDefaults.elevation()
    } else {
        FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp
        )
    }

    // Keep the FAB ripple independent; an idle shadow would tint the shared panel backdrop.
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .padding(vertical = FabVerticalPadding)
            .size(FabSize),
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = elevation
    ) {
        Icon(
            painter = painterResource(if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp),
            contentDescription = stringResource(if (isRunning) R.string.acc_stop else R.string.acc_start),
            modifier = Modifier.size(FabIconSize)
        )
    }
}

/**
 * Release the visual press immediately: native ripple expands and fades once instead of staying
 * as a panel-wide tint while held. The panel clickable still owns the real press and click lifecycle.
 * Focus is forwarded unchanged to Material3's inset ring; hover and drag remain filtered.
 */
@Composable
private fun rememberBarRippleSource(interaction: MutableInteractionSource): MutableInteractionSource {
    val rippleSource = remember(interaction) { MutableInteractionSource() }
    LaunchedEffect(interaction, rippleSource) {
        interaction.interactions.collect { event ->
            when (event) {
                is FocusInteraction -> rippleSource.emit(event)
                is PressInteraction.Press -> {
                    var released = false
                    try {
                        rippleSource.emit(event)
                        rippleSource.emit(PressInteraction.Release(event))
                        released = true
                    } finally {
                        if (!released) rippleSource.tryEmit(PressInteraction.Cancel(event))
                    }
                }
            }
        }
    }
    return rippleSource
}
