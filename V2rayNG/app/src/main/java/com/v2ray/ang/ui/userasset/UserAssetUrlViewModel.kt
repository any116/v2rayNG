package com.v2ray.ang.ui.userasset

import androidx.lifecycle.SavedStateHandle
import com.v2ray.ang.R
import com.v2ray.ang.data.repository.UserAssetRepository
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseEditViewModel
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.util.Utils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UserAssetUrlViewModel internal constructor(
    private val handle: SavedStateHandle,
    private val repo: UserAssetRepository,
    private val isValidUrl: (String) -> Boolean
) : BaseEditViewModel<UserAssetUrlUiState, UserAssetUrlAction>(
    UserAssetUrlUiState(assetId = handle.get<String>(AppRoute.EXTRA_ASSET_ID).orEmpty())
) {

    /** Keeps Android URL validation at the production boundary and snapshot tests on the JVM. */
    @Inject
    constructor(handle: SavedStateHandle, repo: UserAssetRepository) : this(handle, repo, Utils::isValidUrl)

    /**
     * Read-only slice. The edited id is fixed at construction, so the top bar must not depend on
     * the full state read: otherwise every keystroke in the form rebuilds it.
     */
    val isEdit: StateFlow<Boolean> = MutableStateFlow(state.isEdit).asStateFlow()

    private var loadFailed = false
    private var loadJob: Job? = null

    init {
        loadJob = load()
    }

    private fun load(): Job = launch(loading = true, onError = { loadFailed = true; toastError() }) {
        val item = if (state.isEdit) repo.loadAsset(state.assetId) else null
        if (state.isEdit && item == null) {
            loadFailed = true
            toastError()
            return@launch
        }
        val qrCodeUrl = handle.get<String>(AppRoute.EXTRA_ASSET_QRCODE).orEmpty()
        val seed = when {
            item != null -> AssetForm(item.remarks, item.url)
            qrCodeUrl.isNotEmpty() -> AssetForm(File(qrCodeUrl).name, qrCodeUrl)
            else -> AssetForm()
        }
        setState {
            copy(
                initialForm = seed,
                isReady = true
            )
        }
    }

    override fun onAction(action: UserAssetUrlAction) {
        when (action) {
            is UserAssetUrlAction.Save -> if (state.isReady) save { saveSnapshot(action.form) }
            UserAssetUrlAction.Back -> cancel()

            UserAssetUrlAction.DeleteClicked -> setState { copy(showDeleteDialog = true) }
            UserAssetUrlAction.DialogDismiss -> setState { copy(showDeleteDialog = false) }
            UserAssetUrlAction.DialogConfirm -> {
                setState { copy(showDeleteDialog = false) }
                delete()
            }
        }
    }

    private suspend fun saveSnapshot(form: AssetForm): BaseResult? {
        val remarks = form.remarks.trim()
        val url = form.url.trim()

        val remarksError = if (remarks.isEmpty()) R.string.sub_setting_remarks else null
        val urlError = when {
            url.isEmpty() -> R.string.title_url
            !isValidUrl(url) -> R.string.toast_invalid_url
            else -> null
        }
        if (remarksError != null || urlError != null) {
            val errors = buildMap {
                if (remarksError != null) put(AssetField.REMARKS, AssetFieldError(remarksError, form.remarks))
                if (urlError != null) put(AssetField.URL, AssetFieldError(urlError, form.url))
            }
            setState { copy(fieldErrors = errors) }
            return null
        }
        if (state.fieldErrors.isNotEmpty()) setState { copy(fieldErrors = emptyMap()) }

        if (repo.isRemarkDuplicated(remarks, state.assetId)) {
            setState {
                copy(fieldErrors = mapOf(
                    AssetField.REMARKS to AssetFieldError(R.string.msg_remark_is_duplicate, form.remarks)
                ))
            }
            return null
        }

        val id = repo.saveAsset(state.assetId, remarks, url)
        return BaseResult.Saved(id = id, refreshList = true)
    }

    override suspend fun doDelete(): BaseResult? {
        if (!state.isEdit) return null
        loadJob?.join()
        if (loadFailed || !state.isReady) return null
        repo.removeAssetUrl(state.assetId)
        return BaseResult.Deleted(id = state.assetId, refreshList = true)
    }

}
