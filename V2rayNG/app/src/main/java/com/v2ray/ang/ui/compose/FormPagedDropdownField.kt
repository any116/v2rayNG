package com.v2ray.ang.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.v2ray.ang.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlin.math.max
import kotlin.math.roundToInt

private val PagedDropdownMaxHeight = 320.dp
private val PagedDropdownMinHeight = 96.dp
private val PagedDropdownMinWidth = 240.dp
private val PagedDropdownMargin = 8.dp
private val PagedDropdownElevation = 3.dp

/** Debounce before the typed text is pushed upstream. */
private const val QueryDebounceMillis = 250L

private const val DropdownOptionContentType = "dropdown-option"

@Immutable
data class DropdownOption(val value: String, val source: Source) {
    enum class Source { BUILTIN, PROFILE }
    val key: String get() = "${source.name}:$value"
}

/**
 * Paging-backed searchable dropdown.
 *
 * The field itself is always editable and doubles as the search box: typing
 * commits the value through [onValueChange] immediately and pushes a debounced
 * filter to [onQueryChange]. Picking an option writes it to both.
 *
 * ## Why not [ExposedDropdownMenu]
 *
 * [ExposedDropdownMenu] is implemented as a `Column` with `Modifier.verticalScroll`,
 * which cannot host a [LazyColumn] (nested scroll in the same direction is a hard
 * error in Compose). Since paging requires lazy item composition, the popup uses a
 * bare [Popup] instead, keeping the same visual parameters as [ExposedDropdownMenu]:
 * `MenuDefaults`-equivalent surface shape, `PagedDropdownElevation`, matching content
 * padding and the same anchor/IME handling that M3's own
 * `ExposedDropdownMenuPositionProvider` performs.
 *
 * Everything that M3 *can* own is delegated back to it:
 * - [ExposedDropdownMenuBox] tracks expanded state.
 * - [ExposedDropdownMenuAnchorType.PrimaryEditable] opens the menu **without**
 *   stealing focus, so the IME stays bound to the text field.
 * - `Modifier.menuAnchor` installs the `expandable` semantics, `TalkBack`
 *   announcements, keyboard controller wiring, and tap-to-toggle.
 *
 * The visual + behavioral surface therefore matches [FormDropdownField] for
 * everything except what is intrinsic to paging (loading / empty / append states
 * and the debounced filter).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormPagedDropdownField(
    label: String,
    value: String,
    items: LazyPagingItems<DropdownOption>,
    query: String,
    onQueryChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    placeholder: String? = null,
    supportingText: String? = null,
    /** Keep the filter when the menu is dismissed; set true to restore the old behaviour. */
    resetQueryOnDismiss: Boolean = false,
    debounceMillis: Long = QueryDebounceMillis
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Draft is owned here so it survives popup re-creation and pager reloads.
    var draftQuery by rememberSaveable { mutableStateOf(query) }
    var lastPushedQuery by remember { mutableStateOf(query) }
    var anchorRect by remember { mutableStateOf<IntRect?>(null) }

    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)

    // Debounce: draft -> upstream pager. collectLatest cancels the pending delay
    // on each keystroke, so only the last value within debounceMillis is pushed.
    LaunchedEffect(debounceMillis) {
        snapshotFlow { draftQuery }
            .distinctUntilChanged()
            .collectLatest { text ->
                if (text == lastPushedQuery) return@collectLatest
                delay(debounceMillis)
                lastPushedQuery = text
                currentOnQueryChange(text)
            }
    }

    // Reset scroll only when the committed query actually changes.
    LaunchedEffect(query) {
        if (expanded) runCatching { listState.scrollToItem(0) }
    }

    // Back press while the popup is showing is handled by PopupProperties below,
    // which forwards to onDismissRequest. Keep this as a safety net for the IME
    // path where the popup never sees the key event first.
    BackHandler(enabled = expanded) {
        expanded = false
        if (resetQueryOnDismiss) draftQuery = ""
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { newExpanded ->
            if (!enabled) return@ExposedDropdownMenuBox
            expanded = newExpanded
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FieldHorizontalPad, vertical = FieldVerticalPad)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { text ->
                onValueChange(text)   // commit immediately
                draftQuery = text     // filter, debounced
                if (!expanded) expanded = true
            },
            enabled = enabled,
            singleLine = true,
            isError = isError,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = appFieldColors(isError = isError),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    if (!coords.isAttached) return@onGloballyPositioned
                    val origin = coords.positionInWindow()
                    val left = origin.x.roundToInt()
                    val top = origin.y.roundToInt()
                    val rect = IntRect(left, top, left + coords.size.width, top + coords.size.height)
                    if (rect != anchorRect) anchorRect = rect
                }
        )

        val rect = anchorRect
        if (expanded && enabled && rect != null) {
            PagedDropdownPopup(
                anchor = rect,
                items = items,
                listState = listState,
                onDismiss = {
                    expanded = false
                    if (resetQueryOnDismiss) draftQuery = ""
                },
                onPick = { picked ->
                    onValueChange(picked)
                    draftQuery = picked
                    // Don't re-trigger the debounced filter with the value we
                    // just picked - the list is already up to date for it.
                    lastPushedQuery = picked
                    expanded = false
                    focusManager.clearFocus()
                }
            )
        }
    }
}

@Composable
private fun PagedDropdownPopup(
    anchor: IntRect,
    items: LazyPagingItems<DropdownOption>,
    listState: LazyListState,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val windowSize = LocalWindowInfo.current.containerSize
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val marginPx = with(density) { PagedDropdownMargin.roundToPx() }

    // Visible bottom edge after IME.
    val bottomLimit = (windowSize.height - imeBottomPx).coerceAtLeast(0)
    val spaceBelow = bottomLimit - anchor.bottom - marginPx
    val spaceAbove = anchor.top - marginPx
    val minHeightPx = with(density) { PagedDropdownMinHeight.roundToPx() }
    val availablePx = max(max(spaceBelow, spaceAbove), minHeightPx)
    val maxMenuHeight = with(density) {
        minOf(availablePx, PagedDropdownMaxHeight.roundToPx()).toDp()
    }

    val maxMenuWidth = with(density) {
        (windowSize.width - marginPx * 2).coerceAtLeast(1).toDp()
    }
    val anchorWidth = with(density) { anchor.width.toDp() }
    val menuWidth = anchorWidth
        .coerceAtLeast(minOf(PagedDropdownMinWidth, maxMenuWidth))
        .coerceAtMost(maxMenuWidth)

    val positionProvider = remember(anchor, bottomLimit, marginPx) {
        AnchoredDropdownPositionProvider(anchor, bottomLimit, marginPx)
    }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { currentOnDismiss() },
        properties = PopupProperties(
            // Mirror PrimaryEditable: don't steal focus / IME from the anchor.
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = PagedDropdownElevation,
            modifier = Modifier.width(menuWidth)
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = maxMenuHeight)
                    .padding(vertical = FieldVerticalPad)
            ) {
                val refresh = items.loadState.refresh
                when {
                    items.itemCount == 0 && refresh is LoadState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PagedDropdownMinHeight)
                                .padding(FieldHorizontalPad),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = colorScheme.primary
                            )
                        }
                    }

                    items.itemCount == 0 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = PagedDropdownMinHeight)
                                .padding(FieldHorizontalPad),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = stringResource(R.string.msg_no_result),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                    }

                    else -> {
                        // heightIn here is what makes LazyColumn legal inside a Column:
                        // it replaces the unbounded maxHeight from Column with a finite one.
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxMenuHeight)
                        ) {
                            items(
                                count = items.itemCount,
                                key = items.itemKey { it.key },
                                contentType = items.itemContentType { DropdownOptionContentType }
                            ) { index ->
                                val option = items[index] ?: return@items
                                DropdownMenuItem(
                                    text = { Text(option.value, color = colorScheme.primary) },
                                    onClick = { onPick(option.value) }
                                )
                            }
                            if (items.loadState.append is LoadState.Loading) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(FieldVerticalPad),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Positions using the caller-captured anchor rect (window coordinates), ignoring
 * anchorBounds. bottomLimit already accounts for the IME height.
 */
private class AnchoredDropdownPositionProvider(
    private val anchor: IntRect,
    private val bottomLimit: Int,
    private val margin: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val rawX = if (layoutDirection == LayoutDirection.Ltr) {
            anchor.left
        } else {
            anchor.right - popupContentSize.width
        }
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val x = rawX.coerceIn(margin.coerceAtMost(maxX), maxX)

        val spaceBelow = bottomLimit - anchor.bottom
        val spaceAbove = anchor.top
        val y = when {
            popupContentSize.height <= spaceBelow -> anchor.bottom
            popupContentSize.height <= spaceAbove -> anchor.top - popupContentSize.height
            spaceBelow >= spaceAbove -> (bottomLimit - popupContentSize.height).coerceAtLeast(0)
            else -> 0
        }
        return IntOffset(x, y)
    }
}

@Composable
fun rememberPreviewDropdownItems(values: List<String>): LazyPagingItems<DropdownOption> =
    remember(values) {
        flowOf(PagingData.from(values.map { DropdownOption(it, DropdownOption.Source.PROFILE) }))
    }.collectAsLazyPagingItems()

// ===== previews =====

@Preview(showBackground = true)
@Composable
private fun FormPagedDropdownFieldPreview() = AppTheme {
    FormPagedDropdownField(
        label = "Entry proxy",
        value = "node-01",
        items = rememberPreviewDropdownItems(listOf("node-01", "node-02")),
        query = "",
        onQueryChange = {},
        onValueChange = {}
    )
}
