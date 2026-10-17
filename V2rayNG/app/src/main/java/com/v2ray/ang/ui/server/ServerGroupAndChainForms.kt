package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.PagingData
import com.v2ray.ang.R
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.ui.components.DropdownOption
import com.v2ray.ang.ui.components.FormPagedDropdownField
import com.v2ray.ang.ui.components.FormTextField
import com.v2ray.ang.ui.components.ReorderableListItem
import com.v2ray.ang.ui.components.SettingsSwitchItem
import com.v2ray.ang.ui.components.verticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.flow.Flow
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ===== Policy group =====

@Composable
internal fun PolicyGroupForm(
    editor: ServerEditorState,
    remarksError: Int?,
    options: ServerOptions,
    fallbackTags: Flow<PagingData<DropdownOption>>,
    tagQuery: String,
    onTagQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fieldOptions = rememberFieldOptions()
    val allLabel = stringResource(R.string.filter_config_all)
    val subs = options.subscriptions
    val noErrors = emptyMap<ServerField, Int>()

    val typeLabels = fieldOptions.policyGroupTypes
    val typePairs = remember(typeLabels) {
        typeLabels.values.mapIndexed { position, label -> position.toString() to label }
    }
    val subPairs = remember(subs, allLabel) {
        subs.map { it.id to it.name.ifBlank { allLabel } }
    }
    val supportsObservatory by remember(editor) {
        derivedStateOf {
            BalancerStrategyType.from(editor.text(ServerField.GROUP_TYPE)).supportsObservatory
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing)
    ) {
        FormTextField(
            label = stringResource(R.string.server_lab_remarks),
            state = editor[ServerField.REMARKS],
            isError = remarksError != null,
            supportingText = remarksError?.let { stringResource(it) }
        )

        ServerIdDropdownField(
            label = stringResource(R.string.title_policy_group_type),
            state = editor[ServerField.GROUP_TYPE],
            options = typePairs
        )

        ServerIdDropdownField(
            label = stringResource(R.string.title_policy_group_subscription_id),
            state = editor[ServerField.GROUP_SUB_ID],
            options = subPairs
        )

        ServerTextField(
            R.string.title_policy_group_subscription_filter, ServerField.GROUP_FILTER,
            editor, noErrors
        )

        if (supportsObservatory) {
            SettingsSwitchItem(
                title = stringResource(R.string.title_policy_group_test_outbounds),
                checked = editor.groupTestOutbounds,
                onCheckedChange = { editor.groupTestOutbounds = it }
            )
            if (editor.groupTestOutbounds) {
                val fallbackItems = fallbackTags.collectAsLazyPagingItems()
                FormPagedDropdownField(
                    label = stringResource(R.string.title_policy_group_fallback),
                    state = editor[ServerField.GROUP_FALLBACK_TAG],
                    items = fallbackItems,
                    query = tagQuery,
                    onQueryChange = onTagQueryChange
                )
            }
        }
    }
}

// ===== Proxy chain =====

@Composable
internal fun ProxyChainForm(
    editor: ServerEditorState,
    remarksError: Int?,
    chainSlice: (String) -> ChainMemberSlice,
    onRetainMembers: (Set<String>) -> Unit,
    onRemoveChainMember: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val memberIds = editor.chainMembers.map { it.id }
    LaunchedEffect(memberIds) {
        onRetainMembers(memberIds.toSet())
    }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? String ?: return@rememberReorderableLazyListState
        val toId = to.key as? String ?: return@rememberReorderableLazyListState
        editor.moveChainMember(fromId, toId)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.verticalScrollbar(listState),
        contentPadding = contentPadding
    ) {
        item(key = "remarks") {
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                state = editor[ServerField.REMARKS],
                isError = remarksError != null,
                supportingText = remarksError?.let { stringResource(it) },
                modifier = Modifier.padding(horizontal = ServerDimens.ContentHorizontal)
            )
        }
        item(key = "members_title") {
            Text(
                text = stringResource(R.string.server_proxy_chain_members),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(
                    start = ServerDimens.ContentHorizontal,
                    top = ServerDimens.FieldSpacing,
                    bottom = ServerDimens.FieldSpacing
                )
            )
        }
        itemsIndexed(items = editor.chainMembers, key = { _, member -> member.id }) { index, member ->
            ReorderableItem(reorderState, key = member.id) { isDragging ->
                ReorderableListItem(
                    scope = this,
                    isDragging = isDragging,
                    modifier = Modifier.padding(horizontal = ServerDimens.ContentHorizontal)
                ) {
                    ChainMemberRow(
                        ordinal = index + 1,
                        member = member,
                        slice = chainSlice(member.id),
                        onRemove = { onRemoveChainMember(member.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChainMemberRow(
    ordinal: Int,
    member: ServerChainMemberState,
    slice: ChainMemberSlice,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val candidates = slice.candidates.collectAsLazyPagingItems()
    val query by slice.query.collectAsStateWithLifecycle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(ServerDimens.ChainRowPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$ordinal",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = ServerDimens.ContentHorizontal)
                .width(ServerDimens.ChainIndexWidth)
        )
        FormPagedDropdownField(
            label = stringResource(R.string.server_lab_remarks),
            placeholder = stringResource(R.string.server_proxy_chain_member_unselected),
            state = member.remarks,
            items = candidates,
            query = query,
            onQueryChange = slice.onQueryChange,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(
                painterResource(R.drawable.ic_delete_24dp),
                contentDescription = stringResource(R.string.action_delete)
            )
        }
    }
}
