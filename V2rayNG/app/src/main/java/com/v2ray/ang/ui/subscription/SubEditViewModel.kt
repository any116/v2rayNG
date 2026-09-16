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
import com.v2ray.ang.ui.compose.ToastType
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@HiltViewModel
class SubEditViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: SubRepository,
) : BaseEditViewModel<SubEditUiState, SubEditAction>(
    SubEditUiState(subId = handle.get<String>(AppRoute.EXTRA_SUB_GUID).orEmpty())
) {

    private val saver = EditFormSaver(handle, KEY_SAVED)

    private val prevQuery = MutableStateFlow("")
    private val nextQuery = MutableStateFlow("")

    val slices = SubEditSlices(
        prevProfiles = prevQuery.toProfilePager(),
        prevQuery = prevQuery.asStateFlow(),
        onPrevQueryChange = { prevQuery.value = it },
        nextProfiles = nextQuery.toProfilePager(),
        nextQuery = nextQuery.asStateFlow(),
        onNextQueryChange = { nextQuery.value = it },
    )

    private var initial: SubscriptionItem? = null
    private var loadFailed = false
    private var loadJob: Job? = null

    init {
        saver.restore()
            ?.getString(KEY_FORM)
            ?.let { JsonUtil.fromJsonSafe(it, SubEditForm::class.java) }
            ?.let { restored ->
                saver.markDirty()
                setState { copy(form = restored) }
            }
        saver.register { bundle -> bundle.putString(KEY_FORM, JsonUtil.toJson(state.form)) }
        loadJob = load()
    }

    private fun StateFlow<String>.toProfilePager() =
        flatMapLatest { repo.profileRemarkPager(it) }
            .cachedIn(viewModelScope)

    private fun load(): Job = launch(onError = { loadFailed = true; toastError() }) {
        val data = repo.loadEdit(state.subId)
        initial = data.item
        if (state.isEdit && data.item == null) {
            loadFailed = true
            toastError()
        }
        setState {
            copy(
                form = if (saver.dirty) form else data.item.toSubEditForm(),
                confirmRemove = data.confirmRemove,
            )
        }
    }

    override fun onAction(action: SubEditAction) {
        when (action) {
            is SubEditAction.TextChanged -> {
                saver.markDirty()
                setState { copy(form = action.field.set(form, action.value)) }
            }

            is SubEditAction.FlagChanged -> {
                saver.markDirty()
                setState { copy(form = action.flag.set(form, action.value)) }
            }

            SubEditAction.Save -> save()
            SubEditAction.Back -> cancel()
            SubEditAction.DeleteConfirmed -> delete()
        }
    }

    override suspend fun doSave(): BaseResult? {
        if (!awaitLoad()) return null

        val form = state.form
        if (form.remarks.isBlank()) {
            toastError(R.string.sub_setting_remarks)
            return null
        }
        if (form.url.isNotEmpty()) {
            if (!Utils.isValidUrl(form.url)) {
                toastError(R.string.toast_invalid_url)
                return null
            }
            if (!Utils.isValidSubUrl(form.url)) {
                toast(R.string.toast_insecure_url_protocol)
                if (!form.allowInsecureUrl) return null
            }
        }
        if (form.autoUpdate &&
            form.updateInterval.toLongEx() < AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES
        ) {
            toastError(R.string.toast_invalid_update_interval)
            return null
        }

        val chain = repo.validateChain(form.prevProfile, form.nextProfile)
        if (chain.selfReference) {
            toastError(R.string.toast_sub_chain_same_profile)
            return null
        }
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
