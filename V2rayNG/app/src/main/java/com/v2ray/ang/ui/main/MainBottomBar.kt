package com.v2ray.ang.ui.main

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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

// Keep native press ripples without full-area focus, hover or drag state layers.
@OptIn(ExperimentalMaterial3Api::class)
private val BarRippleConfiguration = RippleConfiguration(
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0f,
        focusedAlpha = 0f,
        hoveredAlpha = 0f,
        pressedAlpha = 0.1f
    )
)

@OptIn(ExperimentalMaterial3Api::class)
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

    CompositionLocalProvider(LocalRippleConfiguration provides BarRippleConfiguration) {
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
                    onClick = { onAction(MainAction.StatusBarClick) }
                )
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
    enabled: Boolean,
    isTesting: Boolean,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val actionLabel = stringResource(
        if (isTesting) R.string.acc_cancel_connection_test else R.string.acc_check_connection
    )

    Box(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .border(
                width = BarBorderWidth,
                color = if (focused && enabled) colors.outline else Color.Transparent,
                shape = shape
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                onClickLabel = actionLabel,
                role = Role.Button,
                onClick = onClick
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

@Composable
private fun ServiceFab(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val containerColor = if (isRunning) colors.secondary else colors.surfaceContainerHighest
    val contentColor = if (isRunning) colors.onSecondary else colors.onSurface

    // A fixed surface avoids FAB press transforms and elevation; clickable owns only the ripple.
    Surface(
        modifier = modifier
            .padding(vertical = FabVerticalPadding)
            .size(FabSize),
        shape = FloatingActionButtonDefaults.shape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(BarBorderWidth, if (focused) contentColor else Color.Transparent),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp),
                contentDescription = stringResource(if (isRunning) R.string.acc_stop else R.string.acc_start),
                modifier = Modifier.size(FabIconSize)
            )
        }
    }
}
