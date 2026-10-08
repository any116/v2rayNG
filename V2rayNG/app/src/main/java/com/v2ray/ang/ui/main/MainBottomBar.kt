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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import com.v2ray.ang.ui.components.LocalDarkTheme

private val BarMinHeight = 72.dp
private val BarContentStartPadding = 16.dp
private val BarContentEndPadding = 8.dp
private val BarItemSpacing = 8.dp
private val StatusVerticalPadding = 12.dp
private val FabVerticalPadding = 8.dp
private val BarBorderWidth = 1.dp
private const val BarTintAlpha = 0.7f
private const val BarFallbackAlpha = 0.95f
private const val BarBorderAlpha = 0.35f
private val FabSize = 56.dp
private val FabIconSize = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainBottomBar(
    statusText: String,
    isRunning: Boolean,
    isTesting: Boolean,
    backdrop: MainBackdrop,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val darkTheme = LocalDarkTheme.current
    val shape = MaterialTheme.shapes.extraLarge
    val statusInteraction = remember { MutableInteractionSource() }
    val serviceInteraction = remember { MutableInteractionSource() }
    val statusWave = remember { MainWaveShadowState() }
    val statusFocused by statusInteraction.collectIsFocusedAsState()
    val serviceFocused by serviceInteraction.collectIsFocusedAsState()
    val statusEnabled = isRunning || isTesting
    val statusActionLabel = stringResource(
        if (isTesting) R.string.acc_cancel_connection_test else R.string.acc_check_connection
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = shape,
        color = Color.Transparent,
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
                .mainBackdropEffect(
                    backdrop = backdrop,
                    backgroundColor = colors.background,
                    tintColor = colors.primaryContainer.copy(alpha = BarTintAlpha),
                    fallbackColor = colors.primaryContainer.copy(alpha = BarFallbackAlpha)
                )
                .padding(end = BarContentEndPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BarItemSpacing)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .mainWaveShadow(
                        state = statusWave,
                        color = colors.scrim,
                        darkTheme = darkTheme,
                        enabled = statusEnabled
                    )
                    .border(
                        width = BarBorderWidth,
                        color = if (statusFocused && statusEnabled) colors.outline else Color.Transparent,
                        shape = shape
                    )
                    .clickable(
                        interactionSource = statusInteraction,
                        indication = null,
                        enabled = statusEnabled,
                        onClickLabel = statusActionLabel,
                        role = Role.Button,
                        onClick = {
                            statusWave.emit()
                            onAction(MainAction.StatusBarClick)
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
            // Material FAB has its own ripple; disabling only the status indication isn't enough.
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                FloatingActionButton(
                    onClick = { onAction(MainAction.ToggleService) },
                    modifier = Modifier
                        .padding(vertical = FabVerticalPadding)
                        .size(FabSize)
                        .border(
                            width = BarBorderWidth,
                            color = if (serviceFocused) {
                                if (isRunning) colors.onSecondary else colors.onSurface
                            } else {
                                Color.Transparent
                            },
                            shape = FloatingActionButtonDefaults.shape
                        ),
                    containerColor = if (isRunning) colors.secondary else colors.surfaceContainerHighest,
                    contentColor = if (isRunning) colors.onSecondary else colors.onSurface,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp
                    ),
                    interactionSource = serviceInteraction
                ) {
                    Icon(
                        painter = painterResource(
                            if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
                        ),
                        contentDescription = stringResource(
                            if (isRunning) R.string.acc_stop else R.string.acc_start
                        ),
                        modifier = Modifier.size(FabIconSize)
                    )
                }
            }
        }
    }
}
