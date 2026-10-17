package com.v2ray.ang.ui.routing

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.paging.PagingData
import com.v2ray.ang.AppConfig.TAG_PROXY
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.ui.base.BaseAction
import com.v2ray.ang.ui.base.BaseEvent
import com.v2ray.ang.ui.base.BaseUiState
import com.v2ray.ang.ui.components.DropdownOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class RoutingForm(
    val remarks: String = "",
    val locked: Boolean = false,
    val domain: String = "",
    val ip: String = "",
    val process: String = "",
    val protocol: String = "",
    val network: String = "",
    val port: String = "",
    val outboundTag: String = ""
)

enum class RoutingField { REMARKS, DOMAIN, IP, PROCESS, PROTOCOL, NETWORK, PORT, OUTBOUND }

@Immutable
data class RoutingFieldError(val message: Int, val submittedValue: String)

@Immutable
data class RoutingEditUiState(
    val ruleId: String = "",
    val initialForm: RoutingForm? = null,
    val isReady: Boolean = false,
    val canUseProcess: Boolean = false,
    val fieldErrors: Map<RoutingField, RoutingFieldError> = emptyMap()
) : BaseUiState {
    val isEdit: Boolean get() = ruleId.isNotEmpty()
}

@Stable
class RoutingEditSlices(
    val outboundTags: Flow<PagingData<DropdownOption>>,
    val query: StateFlow<String>,
    val onQueryChange: (String) -> Unit
)

sealed interface RoutingEditAction : BaseAction {
    data class SelectProcess(val process: String) : RoutingEditAction
    data class Save(val form: RoutingForm) : RoutingEditAction
    data object Back : RoutingEditAction
    data object Delete : RoutingEditAction
    data object ConfirmDelete : RoutingEditAction
}

sealed interface RoutingEditEvent : BaseEvent.Platform {
    data object ShowDeleteDialog : RoutingEditEvent
}

internal fun RoutingForm.textValues(): Map<String, String> = mapOf(
    RoutingField.REMARKS.name to remarks,
    RoutingField.DOMAIN.name to domain,
    RoutingField.IP.name to ip,
    RoutingField.PROCESS.name to process,
    RoutingField.PROTOCOL.name to protocol,
    RoutingField.NETWORK.name to network.ifBlank { ROUTING_NETWORK_DEFAULT },
    RoutingField.PORT.name to port,
    RoutingField.OUTBOUND.name to outboundTag
)

fun RulesetItem?.toRoutingForm(): RoutingForm = RoutingForm(
    remarks = this?.remarks.orEmpty(),
    locked = this?.locked == true,
    domain = this?.domain?.joinToString(",").orEmpty(),
    ip = this?.ip?.joinToString(",").orEmpty(),
    process = this?.process?.joinToString(",").orEmpty(),
    protocol = this?.protocol?.joinToString(",").orEmpty(),
    network = this?.network.orEmpty(),
    port = this?.port.orEmpty(),
    outboundTag = this?.outboundTag ?: BUILTIN_OUTBOUND_TAGS.first()
)

fun RulesetItem.applyRoutingForm(form: RoutingForm): RulesetItem = copy(
    remarks = form.remarks,
    locked = form.locked,
    domain = form.domain.toCsvList(),
    ip = form.ip.toCsvList(),
    process = form.process.toCsvList(),
    protocol = form.protocol.toCsvList(),
    port = form.port.nullIfBlank(),
    network = form.network.nullIfBlank(),
    outboundTag = form.outboundTag.trim().ifEmpty { TAG_PROXY }
).also { it.id = this.id }

private fun String.toCsvList(): List<String>? = nullIfBlank()
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
