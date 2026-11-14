package com.v2ray.ang.ui.components

import android.graphics.Rect
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
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

/** Recheck live metadata after the delay, including a same-text pick with no text emission. */
internal suspend fun debounceDropdownQuery(
    text: String,
    debounceMillis: Long,
    currentQuery: () -> String?,
    lastPushedQuery: () -> String,
    onQueryChange: (String) -> Unit
) {
    fun shouldPush() = text == currentQuery() && text != lastPushedQuery()

    if (!shouldPush()) return
    delay(debounceMillis)
    if (shouldPush()) onQueryChange(text)
}

/** Query/visibility metadata only; the caller's [TextFieldState] is the sole editing source. */
@Stable
internal class DropdownQueryState(initialText: String, initialQuery: String) {
    var expanded by mutableStateOf(false)
    private var lastObservedText by mutableStateOf(initialText)
    internal var lastPushedQuery by mutableStateOf(initialQuery)
        private set
    private var suppressedText by mutableStateOf<String?>(initialText)
    internal var resetAtText by mutableStateOf<String?>(null)
        private set
    // An explicit pick invalidates an old search even when text edits conflate back to that query.
    internal var pickGeneration by mutableLongStateOf(0L)
        private set

    internal fun observeText(text: String) {
        if (text == lastObservedText) return
        lastObservedText = text
        if (text != suppressedText) {
            suppressedText = null
            resetAtText = null
        }
    }

    internal fun queryFor(text: String): String? = when {
        text == resetAtText -> ""
        text == suppressedText -> null
        else -> text
    }

    internal fun queryPushed(query: String) {
        lastPushedQuery = query
    }

    fun pick(state: TextFieldState, text: String) {
        // Install suppression before editing, even when the pick equals the pending typed text.
        suppressedText = text
        lastObservedText = text
        resetAtText = null
        expanded = false
        if (state.text.toString() != text) state.setTextAndPlaceCursorAtEnd(text)
        pickGeneration++
    }

    fun dismiss(state: TextFieldState, resetQuery: Boolean) {
        expanded = false
        lastObservedText = state.text.toString()
        if (resetQuery) {
            suppressedText = lastObservedText
            resetAtText = lastObservedText
        }
    }

    companion object {
        val Saver: Saver<DropdownQueryState, Any> = Saver(
            save = {
                listOf(it.expanded, it.lastObservedText, it.lastPushedQuery, it.suppressedText, it.resetAtText)
            },
            restore = { saved -> restoreState(saved) }
        )

        private fun restoreState(saved: Any): DropdownQueryState? {
            val values = saved as? List<*> ?: return null
            val expanded = values.getOrNull(0) as? Boolean ?: return null
            val lastObservedText = values.getOrNull(1) as? String ?: return null
            val suppressedText = values.getOrNull(3) as? String
            val resetAtText = values.getOrNull(4) as? String
            return DropdownQueryState(lastObservedText, "").apply {
                this.expanded = expanded
                this.suppressedText = suppressedText
                this.resetAtText = resetAtText
            }
        }
    }
}

/** Observes only for searching; confirmation never needs to wait for this collector. */
internal suspend fun observeDropdownQueries(
    state: TextFieldState,
    queries: DropdownQueryState,
    debounceMillis: Long,
    onQueryChange: (String) -> Unit
) {
    // Suppression is observable too: pick -> edit back can leave the text equal to the previous
    // emission, but it is now a real query rather than the initial/picked business value.
    snapshotFlow {
        val text = state.text.toString()
        Triple(text, queries.queryFor(text), queries.pickGeneration)
    }
        .distinctUntilChanged()
        .collectLatest { (text, _, _) ->
            if (text != state.text.toString()) return@collectLatest
            queries.observeText(text)
            val query = queries.queryFor(text) ?: return@collectLatest
            debounceDropdownQuery(
                text = query,
                debounceMillis = debounceMillis,
                currentQuery = { queries.queryFor(state.text.toString()) },
                lastPushedQuery = { queries.lastPushedQuery },
                onQueryChange = {
                    queries.queryPushed(it)
                    onQueryChange(it)
                }
            )
        }
}

/**
 * Paging-backed searchable dropdown.
 *
 * The field itself is always editable and doubles as the search box: typing
 * edits the caller-owned [state] and pushes a debounced filter to [onQueryChange].
 * Picking an option edits that same state and suppresses a search for the pick;
 * the already submitted filter is intentionally not reloaded.
 * The label includes a localized search hint so this behavior is discoverable.
 * Only query submission/suppression metadata is saved here; the owner saves the text state.
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FormPagedDropdownField(
    label: String,
    state: TextFieldState,
    items: LazyPagingItems<DropdownOption>,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    placeholder: String? = null,
    supportingText: String? = null,
    /** Search filters are transient interaction state and reset when the menu is dismissed. */
    resetQueryOnDismiss: Boolean = true,
    debounceMillis: Long = QueryDebounceMillis
) {
    val queries = rememberSaveable(state, saver = DropdownQueryState.Saver) {
        DropdownQueryState(state.text.toString(), query)
    }
    var anchorRect by remember { mutableStateOf<IntRect?>(null) }

    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)
    val expandOnUserEdit = remember(queries) {
        InputTransformation {
            if (changes.changeCount > 0) queries.expanded = true
        }
    }

    val dismissDropdown: () -> Unit = {
        queries.dismiss(state, resetQueryOnDismiss)
    }

    LaunchedEffect(state, queries, debounceMillis) {
        observeDropdownQueries(state, queries, debounceMillis) { currentOnQueryChange(it) }
    }

    // Each row owns this list state, so a new query must always start at the first result.
    LaunchedEffect(query) {
        runCatching { listState.scrollToItem(0) }
    }

    ExposedDropdownMenuBox(
        expanded = queries.expanded,
        onExpandedChange = { newExpanded ->
            if (!enabled) return@ExposedDropdownMenuBox
            if (newExpanded) queries.expanded = true else dismissDropdown()
        },
        modifier = modifier
            .fillMaxWidth()
            .then(rememberFormFieldVisibilityModifier())
            .padding(horizontal = FieldHorizontalPad, vertical = FieldVerticalPad)
            .onGloballyPositioned { coords ->
                if (!coords.isAttached) return@onGloballyPositioned
                val origin = coords.positionInWindow()
                val left = origin.x.roundToInt()
                val top = origin.y.roundToInt()
                val rect = IntRect(left, top, left + coords.size.width, top + coords.size.height)
                if (rect != anchorRect) anchorRect = rect
            }
    ) {
        OutlinedTextField(
            state = state,
            enabled = enabled,
            lineLimits = TextFieldLineLimits.SingleLine,
            isError = isError,
            label = { Text(stringResource(R.string.form_searchable_label, label)) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            onKeyboardAction = { focusManager.clearFocus() },
            inputTransformation = expandOnUserEdit,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = queries.expanded) },
            colors = appFieldColors(isError = isError),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled)
                .fillMaxWidth()
        )

        val rect = anchorRect
        if (queries.expanded && enabled && rect != null) {
            PagedDropdownPopup(
                anchor = rect,
                items = items,
                listState = listState,
                onDismiss = dismissDropdown,
                onPick = { picked ->
                    queries.pick(state, picked)
                    focusManager.clearFocus()
                }
            )
        }
    }

    // Register after ExposedDropdownMenuBox so its internal BackHandler cannot close the menu
    // when Back is only intended to hide the IME. Keep the query and text focus intact.
    BackHandler(enabled = queries.expanded && enabled) {
        if (imeVisible) {
            keyboardController?.hide()
        } else {
            dismissDropdown()
        }
    }
}

/** The visible display frame already excludes the IME; convert it to the anchor's window space. */
@Composable
private fun rememberVisibleWindowBounds(): IntRect {
    val view = LocalView.current
    var bounds by remember(view) { mutableStateOf(IntRect.Zero) }

    DisposableEffect(view) {
        val frame = Rect()
        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            view.getWindowVisibleDisplayFrame(frame)
            view.getLocationOnScreen(screenLocation)
            view.getLocationInWindow(windowLocation)
            val offsetX = windowLocation[0] - screenLocation[0]
            val offsetY = windowLocation[1] - screenLocation[1]
            bounds = IntRect(
                frame.left + offsetX,
                frame.top + offsetY,
                frame.right + offsetX,
                frame.bottom + offsetY
            )
        }
        val observer = view.viewTreeObserver
        observer.addOnGlobalLayoutListener(listener)
        listener.onGlobalLayout()
        onDispose {
            val currentObserver = if (observer.isAlive) observer else view.viewTreeObserver
            if (currentObserver.isAlive) currentObserver.removeOnGlobalLayoutListener(listener)
        }
    }

    return bounds
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
    val visibleBounds = rememberVisibleWindowBounds()
    val marginPx = with(density) { PagedDropdownMargin.roundToPx() }
    val horizontalMargin = minOf(marginPx, visibleBounds.width.coerceAtLeast(0) / 2)
    val verticalMargin = minOf(marginPx, visibleBounds.height.coerceAtLeast(0) / 2)
    val viewport = IntRect(
        visibleBounds.left + horizontalMargin,
        visibleBounds.top + verticalMargin,
        visibleBounds.right - horizontalMargin,
        visibleBounds.bottom - verticalMargin
    )
    val spaceBelow = (viewport.bottom - maxOf(anchor.bottom, viewport.top)).coerceAtLeast(0)
    val spaceAbove = (minOf(anchor.top, viewport.bottom) - viewport.top).coerceAtLeast(0)
    val availablePx = max(spaceBelow, spaceAbove)
    // Never force a minimum height that exceeds the actual space above the keyboard.
    if (availablePx == 0 || viewport.width <= 0) return
    val maxMenuHeight = with(density) {
        minOf(availablePx, PagedDropdownMaxHeight.roundToPx()).toDp()
    }

    val maxMenuWidth = with(density) { viewport.width.toDp() }
    val anchorWidth = with(density) { anchor.width.toDp() }
    val menuWidth = anchorWidth
        .coerceAtLeast(minOf(PagedDropdownMinWidth, maxMenuWidth))
        .coerceAtMost(maxMenuWidth)

    val positionProvider = remember(viewport) {
        AnchoredDropdownPositionProvider(viewport)
    }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { currentOnDismiss() },
        properties = PopupProperties(
            // Stay non-focusable and below the IME so keyboard controls are not outside taps.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            dismissOnBackPress = false,
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

/** Use Popup's live anchor bounds so scrolling during IME resize cannot leave a stale position. */
private class AnchoredDropdownPositionProvider(
    private val viewport: IntRect
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val rawX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val maxX = (viewport.right - popupContentSize.width).coerceAtLeast(viewport.left)
        val x = rawX.coerceIn(viewport.left, maxX)

        val spaceBelow = (viewport.bottom - maxOf(anchorBounds.bottom, viewport.top)).coerceAtLeast(0)
        val spaceAbove = (minOf(anchorBounds.top, viewport.bottom) - viewport.top).coerceAtLeast(0)
        val rawY = when {
            popupContentSize.height <= spaceBelow -> anchorBounds.bottom
            popupContentSize.height <= spaceAbove -> anchorBounds.top - popupContentSize.height
            spaceBelow >= spaceAbove -> viewport.bottom - popupContentSize.height
            else -> viewport.top
        }
        val maxY = (viewport.bottom - popupContentSize.height).coerceAtLeast(viewport.top)
        return IntOffset(x, rawY.coerceIn(viewport.top, maxY))
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
        state = rememberTextFieldState("node-01"),
        items = rememberPreviewDropdownItems(listOf("node-01", "node-02")),
        query = "",
        onQueryChange = {}
    )
}
