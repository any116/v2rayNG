package com.v2ray.ang.ui.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.data.repository.ServerRepository
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseEditViewModel
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.ui.base.BaseText
import com.v2ray.ang.ui.base.EditFormSaver
import com.v2ray.ang.ui.components.ToastType
import com.v2ray.ang.util.JsonUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ServerEditViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repository: ServerRepository
) : BaseEditViewModel<ServerUiState, ServerAction>(initialState(handle)) {

    private val subscriptionId: String = handle.get<String>(AppRoute.EXTRA_SUB_ID).orEmpty()

    val documentKey: String = if (state.guid.isNotEmpty()) {
        "profile:${state.guid}"
    } else {
        "new:${state.configType.value}"
    }
    private val legacyForm: ServerForm?
    private val legacyRawContent: String?

    val header: StateFlow<ServerHeader> = uiState
        .map { it.header }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.header)

    // Dropdown filters are transient interaction state, not business data or an editor draft.
    private val tagQuery = MutableStateFlow("")

    private data class ChainSlice(
        val scope: CoroutineScope,
        val slice: ChainMemberSlice
    )

    private val chainSliceLock = Any()
    private val chainSlices = LinkedHashMap<String, ChainSlice>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val slices = ServerSlices(
        fallbackTags = tagQuery
            .flatMapLatest { repository.fallbackTagPager(it) }
            .cachedIn(viewModelScope),
        tagQuery = tagQuery.asStateFlow(),
        onTagQueryChange = { query ->
            tagQuery.value = query
        },
        chainSlice = ::chainSlice,
        retainChainSlices = ::retainChainSlices
    )

    private var initialProfile: ProfileItem = ProfileItem.create(state.configType)
    private var loadFailed = false
    private var loadJob: Job? = null

    init {
        // Read an old-version process snapshot once. The UI saver owns all future drafts.
        val legacy = EditFormSaver(handle, KEY_SAVED).consume()
        legacyForm = legacy?.getString(KEY_FORM)?.let { JsonUtil.fromJsonSafe(it, ServerForm::class.java) }
        legacyRawContent = legacy?.getString(KEY_RAW)
        loadJob = load()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun chainSlice(memberId: String): ChainMemberSlice = synchronized(chainSliceLock) {
        chainSlices.getOrPut(memberId) {
            val scope = CoroutineScope(
                SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Default
            )
            val query = MutableStateFlow("")
            ChainSlice(
                scope = scope,
                slice = ChainMemberSlice(
                    candidates = query
                        .flatMapLatest { repository.chainCandidatePager(it) }
                        .cachedIn(scope),
                    query = query.asStateFlow(),
                    onQueryChange = { query.value = it }
                )
            )
        }.slice
    }

    private fun retainChainSlices(validIds: Set<String>) = synchronized(chainSliceLock) {
        val iterator = chainSlices.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in validIds) {
                entry.value.scope.cancel()
                iterator.remove()
            }
        }
    }

    private fun load(): Job = launch(onError = { loadFailed = true; toastError() }) {
        val guid = state.guid
        val data = repository.loadEdit(guid, state.configType)

        if (guid.isNotEmpty() && data.profile == null) {
            loadFailed = true
            toastError(R.string.toast_failure)
            return@launch
        }

        val configType = data.profile?.configType ?: state.configType
        initialProfile = data.profile ?: ProfileItem.create(configType)

        val options = when (configType) {
            EConfigType.POLICYGROUP -> ServerOptions(subscriptions = data.subscriptions)
            else -> ServerOptions()
        }
        val form = when {
            legacyForm != null -> legacyForm
            data.profile == null -> ServerForm(
                streamSecurity = if (configType == EConfigType.MASQUE) AppConfig.TLS else ""
            )
            else -> ServerForm.from(data.profile)
        }
        val rawContent = when {
            configType != EConfigType.CUSTOM -> ""
            legacyRawContent != null -> legacyRawContent
            else -> data.rawContent
        }

        setState {
            copy(
                configType = configType,
                isRunning = isRunning && data.isSelected,
                initialForm = form,
                isLoaded = true,
                options = options,
                initialRawContent = rawContent
            )
        }
    }

    override fun onAction(action: ServerAction) {
        when (action) {
            is ServerAction.Save -> save { saveSnapshot(action.form, action.rawContent) }
            ServerAction.Back -> cancel()
            ServerAction.DeleteClicked -> platform(ServerEvent.ConfirmDeleteProfile)
            ServerAction.ConfirmDeleteProfile -> delete()
            is ServerAction.FetchCertificate -> fetchCertificate(action.form)
        }
    }

    private fun fetchCertificate(snapshot: ServerForm) {
        if (state.isFetchingCert) return
        setState { copy(isFetchingCert = true) }
        launch(onError = { toastError(R.string.toast_fetch_cert_sha256_failed) }) {
            try {
                if (!awaitLoad()) return@launch
                if (state.configType != EConfigType.HYSTERIA2 && snapshot.streamSecurity != AppConfig.TLS) {
                    toastError(R.string.toast_fetch_cert_sha256_failed)
                    return@launch
                }
                if (snapshot.address.isBlank()) {
                    setState {
                        copy(fieldErrors = fieldErrors + snapshot.validationErrors(
                            mapOf(ServerField.ADDRESS to R.string.server_lab_address)
                        ))
                    }
                    return@launch
                }
                if (state.configType != EConfigType.HYSTERIA2 && (snapshot.port.toIntOrNull() ?: 0) <= 0) {
                    setState {
                        copy(fieldErrors = fieldErrors + snapshot.validationErrors(
                            mapOf(ServerField.PORT to R.string.server_lab_port)
                        ))
                    }
                    return@launch
                }
                setState { copy(fieldErrors = fieldErrors - ServerField.ADDRESS - ServerField.PORT) }
                val sha256 = repository.fetchCertSha256(
                    snapshot.toProfileItem(initialProfile, state.configType)
                )
                if (sha256.isNullOrBlank()) {
                    toastError(R.string.toast_fetch_cert_sha256_failed)
                } else {
                    platform(ServerEvent.CertificateFetched(sha256))
                    toastSuccess(R.string.toast_fetch_cert_sha256_success)
                }
            } finally {
                setState { copy(isFetchingCert = false) }
            }
        }
    }

    private suspend fun saveSnapshot(form: ServerForm, rawContent: String): BaseResult? {
        if (!awaitLoad()) return null
        return when (state.configType) {
            EConfigType.CUSTOM -> saveCustom(form, rawContent)
            EConfigType.POLICYGROUP -> savePolicyGroup(form)
            EConfigType.PROXYCHAIN -> saveProxyChain(form)
            else -> saveStandard(form)
        }
    }

    override suspend fun doDelete(): BaseResult? {
        if (!awaitLoad()) return null
        val guid = state.guid
        if (guid.isEmpty() || repository.isSelectedServer(guid)) {
            toastError(R.string.toast_action_not_allowed)
            return null
        }
        repository.removeProfile(guid)
        return BaseResult.Deleted(id = guid)
    }

    private suspend fun saveStandard(form: ServerForm): BaseResult? {
        val configType = state.configType

        val errors = ServerValidator.validateForm(configType, form)
        if (errors.isNotEmpty()) {
            setState { copy(fieldErrors = form.validationErrors(errors)) }
            return null
        }
        if (state.fieldErrors.isNotEmpty()) setState { copy(fieldErrors = emptyMap()) }

        val profile = form.toProfileItem(initialProfile, configType)
        if (configType == EConfigType.HYSTERIA2 && profile.security.isNullOrBlank()) {
            profile.security = AppConfig.TLS
        }
        ServerValidator.validateProfile(configType, profile)?.let { return fail(it) }

        profile.description = repository.generateDescription(profile)
        applySubscriptionId(profile)
        return BaseResult.Saved(
            id = repository.saveProfile(state.guid, profile),
            restartService = state.isRunning
        )
    }

    private suspend fun saveCustom(form: ServerForm, content: String): BaseResult? {
        if (form.remarks.isBlank()) {
            setState {
                copy(fieldErrors = form.validationErrors(mapOf(ServerField.REMARKS to R.string.server_lab_remarks)))
            }
            return null
        }
        if (state.fieldErrors.isNotEmpty()) setState { copy(fieldErrors = emptyMap()) }

        val parsed = try {
            repository.parseCustomConfig(content)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return fail(BaseText.of(R.string.toast_malformed_json))
        }

        val profile = initialProfile.copy().takeIf { state.isEdit }
            ?: ProfileItem.create(EConfigType.CUSTOM)
        profile.remarks = form.remarks.ifEmpty { parsed?.remarks.orEmpty() }
        profile.server = parsed?.server
        profile.serverPort = parsed?.serverPort
        profile.description = repository.generateDescription(profile)
        applySubscriptionId(profile)

        val savedGuid = repository.saveProfile(state.guid, profile, content)
        return BaseResult.Saved(id = savedGuid, restartService = state.isRunning)
    }

    private suspend fun savePolicyGroup(form: ServerForm): BaseResult? {
        if (form.remarks.isBlank()) {
            setState {
                copy(fieldErrors = form.validationErrors(mapOf(ServerField.REMARKS to R.string.server_lab_remarks)))
            }
            return null
        }
        if (state.fieldErrors.isNotEmpty()) setState { copy(fieldErrors = emptyMap()) }

        val typeIndex = form.groupType.toIntOrNull() ?: 0
        val profile = initialProfile.copy().takeIf { state.isEdit }
            ?: ProfileItem.create(EConfigType.POLICYGROUP)
        profile.remarks = form.remarks.trim()
        profile.policyGroupFilter = form.groupFilter.trim()
        profile.policyGroupType = typeIndex.toString()
        profile.policyGroupSubscriptionId = form.groupSubId
        profile.policyGroupTestOutbounds = form.groupTestOutbounds
        profile.policyGroupFallbackTag = form.groupFallbackTag.trim().takeIf { it.isNotEmpty() }
        profile.description = repository.buildPolicyGroupDescription(
            typeIndex = typeIndex,
            subId = form.groupSubId,
            filter = profile.policyGroupFilter.orEmpty()
        )
        applySubscriptionId(profile)

        return BaseResult.Saved(
            id = repository.saveProfile(state.guid, profile),
            restartService = state.isRunning
        )
    }

    private suspend fun saveProxyChain(form: ServerForm): BaseResult? {
        if (form.remarks.isBlank()) {
            setState {
                copy(fieldErrors = form.validationErrors(mapOf(ServerField.REMARKS to R.string.server_lab_remarks)))
            }
            return null
        }
        if (state.fieldErrors.isNotEmpty()) setState { copy(fieldErrors = emptyMap()) }

        val members = form.chainMembers.map { it.remarks.trim() }
        if (members.any { it.isEmpty() }) {
            return fail(BaseText.of(R.string.server_proxy_chain_members_unselected))
        }
        if (members.size < 2) {
            return fail(BaseText.of(R.string.server_proxy_chain_members_insufficient))
        }
        val invalid = members.filter { remarks ->
            val profile = repository.findProfileByRemarks(remarks)
            profile == null || profile.configType.isComplexType()
        }
        if (invalid.isNotEmpty()) {
            return fail(
                BaseText.of(
                    R.string.server_proxy_chain_members_invalid,
                    invalid.joinToString(", ")
                )
            )
        }

        val profile = initialProfile.copy().takeIf { state.isEdit }
            ?: ProfileItem.create(EConfigType.PROXYCHAIN)
        profile.remarks = form.remarks.trim()
        profile.proxyChainProfiles = members.joinToString(",")
        profile.description = members.joinToString(" -> ")
        applySubscriptionId(profile)

        return BaseResult.Saved(
            id = repository.saveProfile(state.guid, profile),
            restartService = state.isRunning
        )
    }

    private fun applySubscriptionId(profile: ProfileItem) {
        if (profile.subscriptionId.isEmpty() && subscriptionId.isNotEmpty()) {
            profile.subscriptionId = subscriptionId
        }
    }

    private suspend fun awaitLoad(): Boolean {
        loadJob?.join()
        if (loadFailed) toastError(R.string.toast_failure)
        return !loadFailed
    }

    private fun fail(text: BaseText): BaseResult? {
        toast(text, ToastType.ERROR)
        return null
    }

    companion object {
        private const val KEY_SAVED = "server_edit_saved_state"
        private const val KEY_FORM = "form"
        private const val KEY_RAW = "raw"
        private fun initialState(handle: SavedStateHandle): ServerUiState {
            val typeValue = handle.get<Int>(AppRoute.EXTRA_TYPE) ?: EConfigType.VMESS.value
            val guid = handle.get<String>(AppRoute.EXTRA_GUID).orEmpty()
            return ServerUiState(
                configType = EConfigType.fromInt(typeValue) ?: EConfigType.VMESS,
                guid = guid,
                isRunning = (handle.get<Boolean>(AppRoute.EXTRA_RUNNING) ?: false) &&
                        guid.isNotEmpty()
            )
        }
    }

    override fun onCleared() {
        synchronized(chainSliceLock) {
            chainSlices.values.forEach { it.scope.cancel() }
            chainSlices.clear()
        }
        super.onCleared()
    }
}
