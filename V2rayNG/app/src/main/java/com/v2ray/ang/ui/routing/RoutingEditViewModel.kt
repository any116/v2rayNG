package com.v2ray.ang.ui.routing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.repository.RoutingRepository
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseEditViewModel
import com.v2ray.ang.ui.base.BaseResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject

@HiltViewModel
class RoutingEditViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: RoutingRepository
) : BaseEditViewModel<RoutingEditUiState, RoutingEditAction>(
    initialState = RoutingEditUiState(ruleId = handle.get<String>(AppRoute.EXTRA_RULE_ID) ?: "")
) {

    private val outboundQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val slices = RoutingEditSlices(
        outboundTags = outboundQuery
            .flatMapLatest { repo.outboundTagPager(it) }
            .cachedIn(viewModelScope),
        query = outboundQuery.asStateFlow(),
        onQueryChange = { query -> outboundQuery.value = query }
    )

    private var initial: RulesetItem? = null
    private var loadFailed = false
    private var loadJob: Job? = null

    init {
        loadJob = load()
    }

    private fun load(): Job = launch(onError = { loadFailed = true; toastError() }) {
        val data = repo.loadEditData(state.ruleId)
        initial = data.ruleset
        if (state.isEdit && data.ruleset == null) {
            loadFailed = true
            toastError()
            return@launch
        }
        setState {
            copy(
                initialForm = data.ruleset.toRoutingForm(),
                isReady = true,
                canUseProcess = data.canUseProcess
            )
        }
    }

    override fun onAction(action: RoutingEditAction) {
        when (action) {
            is RoutingEditAction.SelectProcess -> selectProcess(action.process)
            is RoutingEditAction.Save -> if (state.isReady) save { saveSnapshot(action.form) }
            RoutingEditAction.Back -> cancel()
            RoutingEditAction.Delete -> platform(RoutingEditEvent.ShowDeleteDialog)
            RoutingEditAction.ConfirmDelete -> delete()
        }
    }

    private fun selectProcess(process: String) {
        if (!state.isReady || !state.canUseProcess) return
        val current = process.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        navigate(AppRoute.AppPicker(selected = current, titleRes = R.string.routing_settings_process_select))
    }

    private suspend fun saveSnapshot(form: RoutingForm): BaseResult? {
        val errors = buildMap {
            if (form.remarks.isBlank()) put(RoutingField.REMARKS, R.string.sub_setting_remarks)
        }
        if (errors.isNotEmpty()) {
            val submittedValues = form.textValues()
            setState {
                copy(fieldErrors = errors.mapValues { (field, message) ->
                    RoutingFieldError(message, submittedValues.getValue(field.name))
                })
            }
            return null
        }
        if (state.fieldErrors.isNotEmpty()) setState { copy(fieldErrors = emptyMap()) }

        val base = initial
        if (state.isEdit && base == null) {
            toastError(R.string.toast_failure)
            return null
        }
        val item = (base ?: RulesetItem()).applyRoutingForm(form)
        val id = if (state.isEdit) {
            if (!repo.updateRule(item)) {
                toastError(R.string.toast_failure)
                return null
            }
            item.id
        } else {
            repo.insertRule(item).takeIf { it.isNotEmpty() } ?: run {
                toastError(R.string.toast_failure)
                return null
            }
        }
        return BaseResult.Saved(id = id, restartService = true, refreshList = false)
    }

    override suspend fun doDelete(): BaseResult? {
        if (!state.isEdit) return null
        if (!awaitLoad()) return null
        if (!repo.removeRule(state.ruleId)) {
            toastError(R.string.toast_failure)
            return null
        }
        return BaseResult.Deleted(restartService = true, refreshList = false)
    }

    /** Never mutate storage from a half-initialised form; returns false when loading failed. */
    private suspend fun awaitLoad(): Boolean {
        loadJob?.join()
        if (loadFailed) toastError(R.string.toast_failure)
        return !loadFailed
    }
}
