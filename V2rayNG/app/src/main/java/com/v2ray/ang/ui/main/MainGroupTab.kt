package com.v2ray.ang.ui.main

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration.Companion.milliseconds

private val TabRowEdgePadding = 16.dp
private val TabMinWidth = 56.dp
private val TabIndicatorCorner = 3.dp
private const val TabRowContainerAlpha = 0.95f

/**
 * Tab labels carry an async COUNT: on a cold start every tab is first laid out as "(0)" and widens
 * once the query lands. Material3's own ScrollableTabData scrolls exactly once per selectedTabIndex
 * change and clamps to the maxValue of that stale layout, which leaves the last tab clipped.
 * Debounce layout churn and re-align afterwards.
 */
private val TabAlignSettleDelay = 48.milliseconds

/**
 * Mirrors Material3's internal ScrollableTabRowScrollSpec (tween / 250ms / FastOutSlowInEasing).
 * Using the same curve is what makes the correction read as a single motion: the user cannot tell
 * that the animation changed hands, only that it landed in the right place.
 */
private val TabScrollSpec = tween<Float>(
    durationMillis = 250,
    easing = FastOutSlowInEasing
)

@Composable
fun GroupTabBar(
    groups: List<GroupMapItem>,
    selectedTabIndex: Int,
    counts: (String) -> StateFlow<Int>,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return
    val safeIndex = selectedTabIndex.coerceIn(0, groups.lastIndex)
    val scrollState = rememberScrollState()
    // Keyed by size only: remarks/count changes must not discard already measured widths.
    val tabWidths = remember(groups.size) { mutableStateListOf(*Array(groups.size) { 0 }) }
    val edgePaddingPx = with(LocalDensity.current) { TabRowEdgePadding.roundToPx() }

    KeepSelectedTabFullyVisible(
        scrollState = scrollState,
        tabWidths = tabWidths,
        tabCount = groups.size,
        selectedIndex = safeIndex,
        edgePaddingPx = edgePaddingPx,
    )

    PrimaryScrollableTabRow(
        selectedTabIndex = safeIndex,
        modifier = modifier.fillMaxWidth(),
        scrollState = scrollState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = TabRowContainerAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        edgePadding = TabRowEdgePadding,
        minTabWidth = TabMinWidth,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                modifier = Modifier
                    .tabIndicatorOffset(selectedTabIndex = safeIndex, matchContentSize = true)
                    .clip(RoundedCornerShape(TabIndicatorCorner)),
                width = Dp.Unspecified,
                color = MaterialTheme.colorScheme.secondary,
            )
        },
        divider = {},
    ) {
        groups.forEachIndexed { index, group ->
            GroupTabItem(
                group = group,
                selected = index == safeIndex,
                counts = counts,
                onClick = { onTabClick(index) },
                modifier = Modifier.onSizeChanged { size ->
                    if (index < tabWidths.size && tabWidths[index] != size.width) {
                        tabWidths[index] = size.width
                    }
                },
            )
        }
    }
}

@Composable
private fun GroupTabItem(
    group: GroupMapItem,
    selected: Boolean,
    counts: (String) -> StateFlow<Int>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAllGroup = group.id.isEmpty()
    val count by remember(group.id) { counts(group.id) }.collectAsStateWithLifecycle()
    val allTitle = stringResource(R.string.filter_config_all)
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        text = {
            Text(
                text = if (isAllGroup) allTitle else "${group.remarks} ($count)",
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    )
}

/**
 * Re-aligns the scroll offset whenever the measured tab strip changes. It only corrects a
 * selected tab that is actually clipped, so a scroll the user performed by hand is never undone:
 * dragging changes neither the widths, the viewport nor maxValue, and therefore emits nothing.
 *
 * animateScrollTo is a MutatorMutex(Default): it preempts Material3's own in-flight
 * Default-priority animation (same priority, latest wins) and waits out any UserInput drag/fling.
 * One takeover, one motion - no manual stop needed.
 */
@Composable
private fun KeepSelectedTabFullyVisible(
    scrollState: ScrollState,
    tabWidths: List<Int>,
    tabCount: Int,
    selectedIndex: Int,
    edgePaddingPx: Int,
) {
    val index by rememberUpdatedState(selectedIndex)
    val padding by rememberUpdatedState(edgePaddingPx)
    val widths by rememberUpdatedState(tabWidths)

    LaunchedEffect(scrollState, tabCount) {
        snapshotFlow {
            TabStripMetrics(
                index = index,
                widths = widths.toList(),
                edgePadding = padding,
                viewport = scrollState.viewportSize,
                maxValue = scrollState.maxValue,
            )
        }
            .distinctUntilChanged()
            // collectLatest cancels the pending delay on every further layout change, so the
            // correction runs once, after the strip has stopped resizing.
            .collectLatest { metrics ->
                if (!metrics.isMeasured) return@collectLatest
                delay(TabAlignSettleDelay)
                val target = metrics.offsetToReveal(scrollState.value) ?: return@collectLatest
                scrollState.animateScrollTo(target, TabScrollSpec)
            }
    }
}

/**
 * ScrollableTabRow lays its tabs out back to back starting at [edgePadding], so tab bounds are
 * derivable from the measured widths alone - no coordinate lookup needed.
 */
private data class TabStripMetrics(
    val index: Int,
    val widths: List<Int>,
    val edgePadding: Int,
    val viewport: Int,
    val maxValue: Int,
) {
    /** maxValue stays Int.MAX_VALUE until the scroll container has been measured once. */
    val isMeasured: Boolean
        get() = viewport > 0 &&
            maxValue != Int.MAX_VALUE &&
            index in widths.indices &&
            widths.none { it <= 0 }

    fun offsetToReveal(current: Int): Int? {
        var left = edgePadding
        for (i in 0 until index) left += widths[i]
        val right = left + widths[index]

        // Keep the edge padding visible on the revealed side as a scroll affordance.
        val target = when {
            current > left -> (left - edgePadding).coerceAtLeast(0)
            current < right - viewport -> (right + edgePadding - viewport).coerceAtMost(maxValue)
            else -> return null // already fully visible
        }
        return target.coerceIn(0, maxValue).takeIf { it != current }
    }
}
