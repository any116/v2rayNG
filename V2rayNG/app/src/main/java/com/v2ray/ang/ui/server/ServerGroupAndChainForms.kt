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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.v2ray.ang.R
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.ui.compose.DropdownOption
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormPagedDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.ReorderableListItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.StringOptions
import com.v2ray.ang.ui.compose.verticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ===== Policy group =====

@Composable
internal fun PolicyGroupForm(
    form: ServerForm,
    remarksError: Int?,
    options: ServerOptions,
    fallbackTags: LazyPagingItems<DropdownOption>,
    tagQuery: String,
    onTagQueryChange: (String) -> Unit,
    onAction: (ServerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldOptions = rememberFieldOptions()
    val allLabel = stringResource(R.string.filter_config_all)
    val subs = options.subscriptions
    val noErrors = emptyMap<ServerField, Int>()

    val typeLabels = fieldOptions.policyGroupTypes
    val typePairs = remember(typeLabels) {
        typeLabels.values.mapIndexed { position, label -> position.toString() to label }
    }
    val typeLabel = typePairs.firstOrNull { it.first == form.groupType }?.second
        ?: typePairs.firstOrNull()?.second.orEmpty()

    val subPairs = remember(subs, allLabel) {
        subs.map { it.id to it.name.ifBlank { allLabel } }
    }
    val subLabels = remember(subPairs) { StringOptions(subPairs.map { it.second }) }
    val subLabel = subPairs.firstOrNull { it.first == form.groupSubId }?.second
        ?: subLabels.firstOrNull().orEmpty()

    val supportsObservatory = BalancerStrategyType.from(form.groupType).supportsObservatory

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ServerDimens.FieldSpacing),
    ) {
        FormTextField(
            label = stringResource(R.string.server_lab_remarks),
            value = form.remarks,
            onValueChange = { onAction(ServerAction.TextChanged(ServerField.REMARKS, it)) },
            isError = remarksError != null,
            supportingText = remarksError?.let { stringResource(it) },
        )

        FormDropdownField(
            label = stringResource(R.string.title_policy_group_type),
            value = typeLabel,
            options = typeLabels,
            onValueChange = { label ->
                val value = typePairs.firstOrNull { it.second == label }?.first
                    ?: return@FormDropdownField
                onAction(ServerAction.TextChanged(ServerField.GROUP_TYPE, value))
            },
        )

        FormDropdownField(
            label = stringResource(R.string.title_policy_group_subscription_id),
            value = subLabel,
            options = subLabels,
            onValueChange = { label ->
                val id = subPairs.firstOrNull { it.second == label }?.first.orEmpty()
                onAction(ServerAction.TextChanged(ServerField.GROUP_SUB_ID, id))
            },
        )

        ServerTextField(
            R.string.title_policy_group_subscription_filter, ServerField.GROUP_FILTER,
            form.groupFilter, onAction, noErrors,
        )

        if (supportsObservatory) {
            SettingsSwitchItem(
                title = stringResource(R.string.title_policy_group_test_outbounds),
                checked = form.groupTestOutbounds,
                onCheckedChange = {
                    onAction(ServerAction.FlagChanged(ServerFlag.GROUP_TEST_OUTBOUNDS, it))
                },
            )
            if (form.groupTestOutbounds) {
                FormPagedDropdownField(
                    label = stringResource(R.string.title_policy_group_fallback),
                    value = form.groupFallbackTag,
                    items = fallbackTags,
                    query = tagQuery,
                    onQueryChange = onTagQueryChange,
                    onValueChange = {
                        onAction(ServerAction.TextChanged(ServerField.GROUP_FALLBACK_TAG, it))
                    }
                )
            }
        }
    }
}

// ===== Proxy chain =====

@Composable
internal fun ProxyChainForm(
    remarks: String,
    remarksError: Int?,
    members: List<ChainMember>,
    candidates: LazyPagingItems<DropdownOption>,
    chainQuery: String,
    onChainQueryChange: (String) -> Unit,
    onAction: (ServerAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? String ?: return@rememberReorderableLazyListState
        val toId = to.key as? String ?: return@rememberReorderableLazyListState
        onAction(ServerAction.ChainMemberMoved(fromId, toId))
    }

    LazyColumn(
        state = listState,
        modifier = modifier.verticalScrollbar(listState),
        contentPadding = contentPadding,
    ) {
        item(key = "remarks") {
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                value = remarks,
                onValueChange = { onAction(ServerAction.TextChanged(ServerField.REMARKS, it)) },
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
                    bottom = ServerDimens.FieldSpacing,
                ),
            )
        }
        itemsIndexed(items = members, key = { _, member -> member.id }) { index, member ->
            ReorderableItem(reorderState, key = member.id) { isDragging ->
                ReorderableListItem(
                    scope = this,
                    isDragging = isDragging,
                    modifier = Modifier.padding(horizontal = ServerDimens.ContentHorizontal)
                ) {
                    ChainMemberRow(
                        ordinal = index + 1,
                        member = member,
                        candidates = candidates,
                        chainQuery = chainQuery,
                        onChainQueryChange = onChainQueryChange,
                        isDragging = isDragging,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChainMemberRow(
    ordinal: Int,
    member: ChainMember,
    candidates: LazyPagingItems<DropdownOption>,
    chainQuery: String,
    onChainQueryChange: (String) -> Unit,
    isDragging: Boolean,
    onAction: (ServerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(ServerDimens.ChainRowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$ordinal",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = ServerDimens.ContentHorizontal)
                .width(ServerDimens.ChainIndexWidth),
        )
        FormPagedDropdownField(
            label = stringResource(R.string.server_lab_remarks),
            placeholder = stringResource(R.string.server_proxy_chain_member_unselected),
            value = member.remarks,
            items = candidates,
            query = chainQuery,
            onQueryChange = onChainQueryChange,
            onValueChange = { onAction(ServerAction.ChainMemberChanged(member.id, it)) },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onAction(ServerAction.ChainMemberRemoveClicked(member.id)) }) {
            Icon(
                painterResource(R.drawable.ic_delete_24dp),
                contentDescription = stringResource(R.string.action_delete),
            )
        }
    }
}
