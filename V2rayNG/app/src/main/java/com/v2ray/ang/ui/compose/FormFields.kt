package com.v2ray.ang.ui.compose

import android.content.res.Configuration
import androidx.annotation.ArrayRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.v2ray.ang.R
import kotlinx.coroutines.flow.flowOf

private val FieldHorizontalPad = 16.dp
private val FieldVerticalPad = 4.dp
private const val SelectionAlpha = 0.4f

private val PagedDropdownMaxHeight = 320.dp
private const val PagedDropdownItemContentType = "paged-dropdown-item"

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
    val keyboardController = LocalSoftwareKeyboardController.current

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { newExpanded ->
            if (!enabled) return@ExposedDropdownMenuBox
            if (!editable && newExpanded) keyboardController?.hide()
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
                .onFocusChanged { focusState ->
                    if (!editable && focusState.isFocused) keyboardController?.hide()
                }
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
    supportingText: String? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { newExpanded ->
            if (!enabled) return@ExposedDropdownMenuBox
            if (!editable && newExpanded) keyboardController?.hide()
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
                .onFocusChanged { focusState ->
                    if (!editable && focusState.isFocused) keyboardController?.hide()
                }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.msg_enter_keywords)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null) },
                colors = appFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FieldHorizontalPad, vertical = FieldVerticalPad)
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .heightIn(max = PagedDropdownMaxHeight)
                    .verticalScrollbar(listState)
            ) {
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.key },
                    contentType = items.itemContentType { PagedDropdownItemContentType }
                ) { index ->
                    val option = items[index] ?: return@items
                    DropdownMenuItem(
                        text = { Text(option.value) },
                        onClick = {
                            onValueChange(option.value)
                            expanded = false
                            onQueryChange("")
                            focusManager.clearFocus()
                        }
                    )
                }
                if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                    item(contentType = PagedDropdownItemContentType) {
                        Text(
                            text = stringResource(R.string.msg_no_result),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(FieldHorizontalPad)
                        )
                    }
                }
            }
        }
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
        editable = true
    )
}
