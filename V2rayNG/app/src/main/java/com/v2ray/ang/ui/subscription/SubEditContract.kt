package com.v2ray.ang.ui.subscription

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.paging.PagingData
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.extension.toLongEx
import com.v2ray.ang.ui.base.BaseAction
import com.v2ray.ang.ui.base.BaseUiState
import com.v2ray.ang.ui.components.DropdownOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Immutable
data class SubEditForm(
    val remarks: String = "",
    val url: String = "",
    val userAgent: String = "",
    val requestHeaders: String = "",
    val filter: String = "",
    val enabled: Boolean = true,
    val autoUpdate: Boolean = false,
    val updateInterval: String = SubscriptionItem().updateInterval.toString(),
    val allowInsecureUrl: Boolean = false,
    val prevProfile: String = "",
    val nextProfile: String = ""
)

enum class SubField {
    REMARKS,
    URL,
    USER_AGENT,
    REQUEST_HEADERS,
    FILTER,
    UPDATE_INTERVAL,
    PREV_PROFILE,
    NEXT_PROFILE
}

/** Validation metadata belongs to the submitted value, never to a later edit. */
@Immutable
data class SubEditFieldError(
    val message: Int,
    val submittedValue: String,
    val submittedFlag: Boolean? = null
)

@Immutable
data class SubEditUiState(
    val subId: String = "",
    val initialForm: SubEditForm? = null,
    val isReady: Boolean = false,
    val confirmRemove: Boolean = false,
    val fieldErrors: Map<SubField, SubEditFieldError> = emptyMap()
) : BaseUiState {
    val isEdit: Boolean get() = subId.isNotEmpty()
}

@Stable
class SubEditSlices(
    val prevProfiles: Flow<PagingData<DropdownOption>>,
    val prevQuery: StateFlow<String>,
    val onPrevQueryChange: (String) -> Unit,
    val nextProfiles: Flow<PagingData<DropdownOption>>,
    val nextQuery: StateFlow<String>,
    val onNextQueryChange: (String) -> Unit
)

sealed interface SubEditAction : BaseAction {
    data class Save(val form: SubEditForm) : SubEditAction
    data object Back : SubEditAction
    data object DeleteConfirmed : SubEditAction
}

internal fun SubEditForm.textValues(): Map<String, String> = mapOf(
    SubField.REMARKS.name to remarks,
    SubField.URL.name to url,
    SubField.USER_AGENT.name to userAgent,
    SubField.REQUEST_HEADERS.name to requestHeaders,
    SubField.FILTER.name to filter,
    SubField.UPDATE_INTERVAL.name to updateInterval,
    SubField.PREV_PROFILE.name to prevProfile,
    SubField.NEXT_PROFILE.name to nextProfile
)

fun SubscriptionItem?.toSubEditForm(): SubEditForm {
    val item = this ?: SubscriptionItem()
    return SubEditForm(
        remarks = item.remarks,
        url = item.url,
        userAgent = item.userAgent.orEmpty(),
        requestHeaders = item.requestHeaders.orEmpty(),
        filter = item.filter.orEmpty(),
        enabled = item.enabled,
        autoUpdate = item.autoUpdate,
        updateInterval = item.updateInterval.toString(),
        allowInsecureUrl = item.allowInsecureUrl,
        prevProfile = item.prevProfile.orEmpty(),
        nextProfile = item.nextProfile.orEmpty()
    )
}

fun SubscriptionItem.applySubEditForm(form: SubEditForm): SubscriptionItem = copy().apply {
    remarks = form.remarks
    url = form.url
    userAgent = form.userAgent
    requestHeaders = form.requestHeaders
    filter = form.filter
    enabled = form.enabled
    autoUpdate = form.autoUpdate
    updateInterval = form.updateInterval.toLongEx()
    allowInsecureUrl = form.allowInsecureUrl
    prevProfile = form.prevProfile
    nextProfile = form.nextProfile
}
