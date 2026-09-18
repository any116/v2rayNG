package com.v2ray.ang.ui.compose

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.ArrayRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
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
import com.v2ray.ang.R
import kotlinx.coroutines.flow.flowOf
import kotlin.math.max
import kotlin.math.roundToInt

private val FieldHorizontalPad = 16.dp
private val FieldVerticalPad = 4.dp
private const val SelectionAlpha = 0.4f

private val PagedDropdownMaxHeight = 320.dp
private val PagedDropdownMinHeight = 96.dp
private val PagedDropdownMinWidth = 240.dp
private val PagedDropdownMargin = 8.dp
private val PagedDropdownElevation = 3.dp

@Immutable
class StringOptions(val values: List<String>) {
    val size: Int get() = values.size
    fun isEmpty(): Boolean = values.isEmpty()
    fun isNotEmpty(): Boolean = values.isNotEmpty()
    fun firstOrNull(): String? = values.firstOrNull()
    operator fun contains(value: String): Boolean = values.contains(value)
    override fun equals(other: Any?): Boolean =
        this === other || (other is StringOptions && values == other.values)
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = "StringOptions($values)"

    companion object {
        val Empty = StringOptions(emptyList())
    }
}

@Immutable
data class DropdownOption(val value: String, val source: Source) {
    enum class Source { BUILTIN, PROFILE }
    val key: String get() = "${source.name}:$value"
}

fun List<String>.toStringOptions(): StringOptions =
    if (isEmpty()) StringOptions.Empty else StringOptions(this)

fun Array<out String>.toStringOptions(): StringOptions =
    if (isEmpty()) StringOptions.Empty else StringOptions(asList())

@Composable
fun rememberStringOptions(@ArrayRes id: Int): StringOptions {
    val resources = LocalResources.current
    return remember(id, resources) { StringOptions(resources.getStringArray(id).asList()) }
}

@Composable
internal fun appFieldColors(borderless: Boolean = false): TextFieldColors {
    val secondary = MaterialTheme.colorScheme.secondary
    val border = if (borderless) Color.Transparent else Color.Unspecified
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedBorderColor = if (borderless) border else OutlinedTextFieldDefaults.colors().focusedIndicatorColor,
        unfocusedBorderColor = if (borderless) border else OutlinedTextFieldDefaults.colors().unfocusedIndicatorColor,
        cursorColor = secondary,
        selectionColors = remember(secondary) {
            TextSelectionColors(
                handleColor = secondary,
                backgroundColor = secondary.copy(alpha = SelectionAlpha)
            )
        }
    )
}

@Composable
fun FormTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    supportingText: String? = null,
    maxLines: Int = 15
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = false,
        maxLines = maxLines,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = appFieldColors(),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FieldHorizontalPad, vertical = FieldVerticalPad)
    )
}

/**
 * Dropdown for small option sets. Content is not lazy, so ExposedDropdownMenu's
 * IntrinsicSize.Max does not cause issues; keep the original implementation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDropdownField(
    label: String,
    value: String,
    options: StringOptions,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    enabled: Boolean = true,
    placeholder: String? = null,
    supportingText: String? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val menuScrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

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
            onValueChange = { if (editable) onValueChange(it) },
            readOnly = !editable,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = appFieldColors(),
            modifier = Modifier
                .menuAnchor(
                    type = if (editable) ExposedDropdownMenuAnchorType.PrimaryEditable
                    else ExposedDropdownMenuAnchorType.PrimaryNotEditable
                )
                .fillMaxWidth()
                // When not editable, forbid focus entirely; more reliable than hiding the keyboard afterwards.
                .then(if (editable) Modifier else Modifier.focusProperties { canFocus = false })
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.verticalScrollbar(menuScrollState),
            scrollState = menuScrollState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            options.values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                        focusManager.clearFocus()
                    }
                )
            }
        }
    }
}

/*** Paging-backed searchable dropdown. */
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
    editable: Boolean = false,
    enabled: Boolean = true,
    placeholder: String? = null,
    supportingText: String? = null,
    autoFocusSearch: Boolean = false
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var anchorRect by remember { mutableStateOf<IntRect?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val overlaySource = remember { MutableInteractionSource() }

    val close: () -> Unit = {
        expanded = false
        onQueryChange("")
    }

    LaunchedEffect(query, expanded) {
        if (expanded) runCatching { listState.scrollToItem(0) }
    }

    BackHandler(enabled = expanded) { close() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FieldHorizontalPad, vertical = FieldVerticalPad)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { text ->
                if (editable) {
                    onValueChange(text)
                    onQueryChange(text)
                    if (!expanded) expanded = true
                }
            },
            readOnly = !editable,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = appFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (editable) Modifier else Modifier.focusProperties { canFocus = false })
                .onFocusChanged { state ->
                    if (!editable) return@onFocusChanged
                    if (state.isFocused) expanded = true else if (expanded) close()
                }
                .onGloballyPositioned { coords ->
                    if (!coords.isAttached) return@onGloballyPositioned
                    val origin = coords.positionInWindow()
                    val left = origin.x.roundToInt()
                    val top = origin.y.roundToInt()
                    val rect = IntRect(left, top, left + coords.size.width, top + coords.size.height)
                    if (rect != anchorRect) anchorRect = rect
                }
        )

        // Transparent overlay declared after the text field, so it wins hit testing;
        // clicks are therefore not consumed by the OutlinedTextField.
        if (!editable) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        enabled = enabled,
                        interactionSource = overlaySource,
                        indication = null,
                        role = Role.DropdownList,
                        onClickLabel = label
                    ) {
                        focusManager.clearFocus()
                        if (expanded) close() else expanded = true
                    }
            )
        }
    }

    val rect = anchorRect
    if (expanded && enabled && rect != null) {
        PagedDropdownPopup(
            anchor = rect,
            items = items,
            listState = listState,
            query = query,
            onQueryChange = onQueryChange,
            showSearchField = !editable,
            autoFocusSearch = autoFocusSearch,
            focusable = !editable,
            onDismiss = close,
            onPick = { picked ->
                onValueChange(picked)
                close()
                focusManager.clearFocus()
            }
        )
    }
}

@Composable
private fun PagedDropdownPopup(
    anchor: IntRect,
    items: LazyPagingItems<DropdownOption>,
    listState: LazyListState,
    query: String,
    onQueryChange: (String) -> Unit,
    showSearchField: Boolean,
    autoFocusSearch: Boolean,
    focusable: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val windowSize = LocalWindowInfo.current.containerSize
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val marginPx = with(density) { PagedDropdownMargin.roundToPx() }

    // Visible bottom edge after the IME is taken into account.
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

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = focusable,
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
                if (showSearchField) {
                    val focusRequester = remember { FocusRequester() }
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        placeholder = {
                            Text(
                                stringResource(R.string.msg_enter_keywords),
                                color = colorScheme.primary.copy(alpha = 0.7f)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_search_24dp),
                                contentDescription = null,
                                tint = colorScheme.primary
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colorScheme.primary,
                            unfocusedTextColor = colorScheme.primary,
                            cursorColor = colorScheme.primary,
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.primary.copy(alpha = 0.7f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FieldHorizontalPad, vertical = FieldVerticalPad)
                            .focusRequester(focusRequester)
                    )
                    LaunchedEffect(autoFocusSearch) {
                        if (autoFocusSearch) {
                            withFrameNanos { }          // Wait for the node to be attached, otherwise FocusRequester is not initialized.
                            runCatching { focusRequester.requestFocus() }
                        }
                    }
                }

                val refresh = items.loadState.refresh
                when {
                    items.itemCount == 0 && refresh is LoadState.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
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
                        Text(
                            text = stringResource(R.string.msg_no_result),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.padding(FieldHorizontalPad)
                        )
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)   // Outer heightIn sets the upper bound; shrinks to content when smaller.
                        ) {
                            // No key: when Paging merges BUILTIN/PROFILE, values may collide,
                            // and duplicate keys make LazyColumn throw. If upstream guarantees uniqueness, use items.itemKey { it.key }.
                            items(count = items.itemCount) { index ->
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
 * Positions using the anchor rect captured by the caller (window coordinates),
 * ignoring the anchorBounds passed into the Popup.
 * bottomLimit already accounts for the IME height.
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
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FormTextFieldPreview() = AppTheme {
    Column {
        FormTextField(label = "Address", value = "example.com", onValueChange = {})
        FormTextField(
            label = "Remarks",
            value = "A remark long enough to wrap across more than one visual line in the field",
            onValueChange = {}
        )
        FormTextField(label = "Disabled", value = "", enabled = false, onValueChange = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun FormDropdownFieldPreview() = AppTheme {
    FormDropdownField(
        label = "Entry proxy",
        value = "tcp",
        options = listOf("tcp", "ws", "grpc").toStringOptions(),
        supportingText = "Added before every profile in this subscription",
        onValueChange = {}
    )
}

@Preview(showBackground = true)
@Composable
private fun FormPagedDropdownFieldPreview() = AppTheme {
    FormPagedDropdownField(
        label = "Entry proxy",
        value = "node-01",
        items = rememberPreviewDropdownItems(listOf("node-01", "node-02")),
        query = "",
        onQueryChange = {},
        onValueChange = {},
        editable = false
    )
}
