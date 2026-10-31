package com.v2ray.ang.ui.main

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.components.AppSearchState
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.AppTopBar
import com.v2ray.ang.ui.components.ScrollbarConfig
import com.v2ray.ang.ui.components.verticalScrollbar
import kotlin.math.roundToInt

private val MainMenuWindowMargin = 20.dp
private val MainMenuScrollbarConfig = ScrollbarConfig.Default.copy(isFadeEnabled = false)

@Composable
fun MainTopBar(
    isLoading: Boolean,
    isSearchActive: Boolean,
    searchInput: TextFieldState,
    onMenuClick: () -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onAction: (MainAction) -> Unit,
    onShowDialog: (MainDialog) -> Unit,
    bottomOverlayHeightPx: Int,
    modifier: Modifier = Modifier,
    suppressAnimations: Boolean = false
) {
    var showImportMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var importMenuAnchor by remember { mutableStateOf<IntRect?>(null) }
    var moreMenuAnchor by remember { mutableStateOf<IntRect?>(null) }
    val importAnchor = remember { MainMenuAnchor() }
    val moreAnchor = remember { MainMenuAnchor() }

    val importScrollState = rememberScrollState()
    val moreScrollState = rememberScrollState()
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val topInset = WindowInsets.statusBars.getTop(density)
    val bottomInset = WindowInsets.navigationBars.getBottom(density)
    // The bottom bar is overlaid on the page, so its measured top edge is another safe bottom limit.
    val bottomLimit = minOf(
        (windowHeight - bottomInset).coerceAtLeast(topInset),
        (windowHeight - bottomOverlayHeightPx.coerceAtLeast(0)).coerceAtLeast(topInset)
    )
    val menuMargin = with(density) { MainMenuWindowMargin.roundToPx() }
    val maxImportMenuHeight = with(density) {
        calculateMainMenuMaxHeight(
            anchorBounds = importMenuAnchor,
            topInset = topInset,
            bottomLimit = bottomLimit,
            margin = menuMargin
        ).toDp()
    }
    val maxMoreMenuHeight = with(density) {
        calculateMainMenuMaxHeight(
            anchorBounds = moreMenuAnchor,
            topInset = topInset,
            bottomLimit = bottomLimit,
            margin = menuMargin
        ).toDp()
    }

    AppTopBar(
        modifier = modifier,
        title = stringResource(R.string.title_server),
        onBackClick = onMenuClick,
        isLoading = isLoading,
        suppressAnimations = suppressAnimations,
        searchState = if (isSearchActive) AppSearchState(
            isActive = true,
            state = searchInput,
            placeholder = stringResource(R.string.menu_item_search)
        ) else null,
        onSearchClose = onSearchClose,
        navigationIcon = {
            if (isSearchActive) {
                IconButton(onClick = onSearchClose) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back_24dp),
                        contentDescription = stringResource(R.string.acc_back)
                    )
                }
            } else {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        painterResource(R.drawable.ic_menu_24dp),
                        contentDescription = stringResource(R.string.acc_open_menu)
                    )
                }
            }
        },
        actions = {
            if (!isSearchActive) {
                IconButton(onClick = onSearchOpen) {
                    Icon(
                        painterResource(R.drawable.ic_search_24dp),
                        contentDescription = stringResource(R.string.acc_search)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .wrapContentSize(Alignment.TopEnd)
                    .onGloballyPositioned { coordinates ->
                        importAnchor.coordinates = coordinates
                        if (showImportMenu) importMenuAnchor = importAnchor.bounds()
                    }
            ) {
                IconButton(onClick = {
                    importMenuAnchor = importAnchor.bounds()
                    showImportMenu = true
                }) {
                    Icon(
                        painterResource(R.drawable.ic_add_24dp),
                        contentDescription = stringResource(R.string.acc_add)
                    )
                }
                DropdownMenu(
                    expanded = showImportMenu,
                    onDismissRequest = { showImportMenu = false },
                    scrollState = importScrollState,
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .heightIn(max = maxImportMenuHeight)
                        .verticalScrollbar(importScrollState, MainMenuScrollbarConfig)
                ) {
                    ImportMenuContent { action ->
                        showImportMenu = false
                        onAction(action)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .wrapContentSize(Alignment.TopEnd)
                    .onGloballyPositioned { coordinates ->
                        moreAnchor.coordinates = coordinates
                        if (showMoreMenu) moreMenuAnchor = moreAnchor.bounds()
                    }
            ) {
                IconButton(onClick = {
                    moreMenuAnchor = moreAnchor.bounds()
                    showMoreMenu = true
                }) {
                    Icon(
                        painterResource(R.drawable.ic_more_vert_24dp),
                        contentDescription = stringResource(R.string.acc_more)
                    )
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    scrollState = moreScrollState,
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .heightIn(max = maxMoreMenuHeight)
                        .verticalScrollbar(moreScrollState, MainMenuScrollbarConfig)
                ) {
                    MoreMenuContent(
                        onAction = { action ->
                            showMoreMenu = false
                            onAction(action)
                        },
                        onShowDialog = { dialog ->
                            showMoreMenu = false
                            onShowDialog(dialog)
                        }
                    )
                }
            }
        }
    )
}

/** Keeps moving coordinates out of snapshot state until the corresponding popup is actually used. */
private class MainMenuAnchor {
    var coordinates: LayoutCoordinates? = null

    fun bounds(): IntRect? {
        val current = coordinates?.takeIf { it.isAttached } ?: return null
        val origin = current.positionInWindow()
        val left = origin.x.roundToInt()
        val top = origin.y.roundToInt()
        return IntRect(left, top, left + current.size.width, top + current.size.height)
    }
}

private fun calculateMainMenuMaxHeight(
    anchorBounds: IntRect?,
    topInset: Int,
    bottomLimit: Int,
    margin: Int
): Int {
    val topLimit = topInset.coerceAtLeast(0)
    val safeBottomLimit = bottomLimit.coerceAtLeast(topLimit)
    if (anchorBounds == null) return (safeBottomLimit - topLimit - margin).coerceAtLeast(0)

    val spaceBelow = safeBottomLimit - anchorBounds.bottom - margin
    val spaceAbove = anchorBounds.top - topLimit - margin
    return maxOf(spaceBelow, spaceAbove).coerceAtLeast(0)
}

@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainTopBarSearchPreview() = AppTheme {
    MainTopBar(
        isLoading = false,
        isSearchActive = true,
        searchInput = rememberTextFieldState(initialText = "v2ray"),
        onMenuClick = {},
        onSearchOpen = {},
        onSearchClose = {},
        onAction = {},
        onShowDialog = {},
        bottomOverlayHeightPx = 0
    )
}
