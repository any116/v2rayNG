package com.v2ray.ang.ui.subscription

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.extension.toLongEx
import com.v2ray.ang.data.repository.SubRepository
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseEditViewModel
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.ui.base.BaseText
import com.v2ray.ang.ui.base.EditFormSaver
import com.v2ray.ang.ui.components.ToastType
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@HiltViewModel
class SubEditViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: SubRepository
) : BaseEditViewModel<SubEditUiState, SubEditAction>(
    SubEditUiState(subId = handle.get<String>(AppRoute.EXTRA_SUB_GUID).orEmpty())
) {

    private val prevQuery = MutableStateFlow("")
    private val nextQuery = MutableStateFlow("")

    val slices = SubEditSlices(
        prevProfiles = prevQuery.toProfilePager(),
        prevQuery = prevQuery.asStateFlow(),
        onPrevQueryChange = { query -> prevQuery.value = query },
        nextProfiles = nextQuery.toProfilePager(),
        nextQuery = nextQuery.asStateFlow(),
        onNextQueryChange = { query -> nextQuery.value = query }
    )

    private var initial: SubscriptionItem? = null
    private var loadFailed = false
    private var loadJob: Job? = null

    init {
        val legacyForm = EditFormSaver(handle, KEY_SAVED).consume()
            ?.getString(KEY_FORM)
            ?.let { JsonUtil.fromJsonSafe(it, SubEditForm::class.java) }
        loadJob = load(legacyForm)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun StateFlow<String>.toProfilePager() =
        flatMapLatest { repo.profileRemarkPager(it) }
            .cachedIn(viewModelScope)

    private fun load(legacyForm: SubEditForm?): Job = launch(onError = { loadFailed = true; toastError() }) {
        val data = repo.loadEdit(state.subId)
        initial = data.item
        if (state.isEdit && data.item == null) {
            loadFailed = true
            toastError()
            return@launch
        }
        setState {
            copy(
                initialForm = legacyForm ?: data.item.toSubEditForm(),
                isReady = true,
                confirmRemove = data.confirmRemove
            )
        }
    }

    override fun onAction(action: SubEditAction) {
        when (action) {
            is SubEditAction.Save -> if (state.isReady) save { saveSnapshot(action.form) }
            SubEditAction.Back -> cancel()
            SubEditAction.DeleteConfirmed -> delete()
        }
    }

    private suspend fun saveSnapshot(form: SubEditForm): BaseResult? {
        val errors = buildMap {
            if (form.remarks.isBlank()) put(SubField.REMARKS, R.string.sub_setting_remarks)

            if (form.url.isNotEmpty()) {
                when {
                    !Utils.isValidUrl(form.url) ->
                        put(SubField.URL, R.string.toast_invalid_url)
                    !Utils.isValidSubUrl(form.url) && !form.allowInsecureUrl ->
                        put(SubField.URL, R.string.toast_insecure_url_protocol)
                }
            }

            if (form.autoUpdate &&
                form.updateInterval.toLongEx() < AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES
            ) {
                put(SubField.UPDATE_INTERVAL, R.string.toast_invalid_update_interval)
            }
        }

        if (errors.isNotEmpty()) {
            val submittedValues = form.textValues()
            setState {
                copy(fieldErrors = errors.mapValues { (field, message) ->
                    SubEditFieldError(
                        message = message,
                        submittedValue = submittedValues.getValue(field.name),
                        submittedFlag = when (field) {
                            SubField.URL -> form.allowInsecureUrl
                            SubField.UPDATE_INTERVAL -> form.autoUpdate
                            else -> null
                        }
                    )
                })
            }
            return null
        }
        if (state.fieldErrors.isNotEmpty()) setState { copy(fieldErrors = emptyMap()) }

        if (form.url.isNotEmpty() &&
            Utils.isValidUrl(form.url) &&
            !Utils.isValidSubUrl(form.url) &&
            form.allowInsecureUrl
        ) {
            toast(R.string.toast_insecure_url_protocol)
        }

        val chain = repo.validateChain(form.prevProfile, form.nextProfile)
        if (chain.selfReference) return null
        chain.missingProfiles.firstOrNull()?.let { missing ->
            toast(BaseText.of(R.string.toast_sub_chain_profile_missing, missing), ToastType.ERROR)
            return null
        }

        val item = (initial ?: SubscriptionItem()).applySubEditForm(form)
        repo.save(state.subId, item)
        return BaseResult.Saved(id = state.subId, refreshList = true)
    }

    override suspend fun doDelete(): BaseResult? {
        if (!state.isEdit) return null
        if (!awaitLoad()) return null
        repo.remove(state.subId)
        return BaseResult.Deleted(id = state.subId, refreshList = true)
    }

    private suspend fun awaitLoad(): Boolean {
        loadJob?.join()
        if (loadFailed) toastError(R.string.toast_failure)
        return !loadFailed
    }

    private companion object {
        const val KEY_SAVED = "sub_edit_saved_state"
        const val KEY_FORM = "form"
    }
}
