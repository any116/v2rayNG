package com.v2ray.ang.ui.server

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.repository.ServerRepository
import com.v2ray.ang.dto.ServerEditData
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseEvent
import com.v2ray.ang.ui.base.BaseResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ServerEditSnapshotTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun pendingLoadPublishesNoEmptyFormAndRestoredDraftWinsAfterMetadataArrives() = runTest(dispatcher) {
        val load = CompletableDeferred<ServerEditData>()
        val repo = FakeRepository(dispatcher).apply { loadBlock = { load.await() } }
        val vm = viewModel(repo)
        val editor = ServerEditorState().apply { initialize(validForm, "restored raw") }
        editor[ServerField.ADDRESS].setTextAndPlaceCursorAtEnd("restored address")
        val restoredSnapshot = editor.snapshot()

        assertFalse(vm.uiState.value.isLoaded)
        assertNull(vm.uiState.value.initialForm)
        runCurrent()
        load.complete(ServerEditData(profile = profile(), rawContent = "database raw"))
        runCurrent()
        val loaded = vm.uiState.value

        assertTrue(loaded.isLoaded)
        assertFalse(editor.initialize(checkNotNull(loaded.initialForm), loaded.initialRawContent))
        assertEquals(restoredSnapshot, editor.snapshot())
    }

    @Test
    fun acceptedSaveCapturesItsActionWhileAwaitingLoadAndRejectsASecondMutation() = runTest(dispatcher) {
        val load = CompletableDeferred<ServerEditData>()
        val repo = FakeRepository(dispatcher).apply { loadBlock = { load.await() } }
        val vm = viewModel(repo)
        val editor = ServerEditorState().apply { initialize(validForm, "first raw") }
        val first = editor.snapshot()
        val events = mutableListOf<BaseEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }

        vm.onAction(ServerAction.Save(first.form, first.rawContent))
        editor[ServerField.REMARKS].setTextAndPlaceCursorAtEnd("second save")
        editor[ServerField.ADDRESS].setTextAndPlaceCursorAtEnd("later address")
        editor.rawContent.setTextAndPlaceCursorAtEnd("later raw")
        val second = editor.snapshot()
        vm.onAction(ServerAction.Save(second.form, second.rawContent))
        runCurrent()
        assertTrue(repo.saved.isEmpty())

        load.complete(ServerEditData(profile = profile().apply { subscriptionId = "loaded-subscription" }))
        runCurrent()

        val saved = repo.saved.single()
        assertEquals(first.form.remarks, saved.profile.remarks)
        assertEquals(first.form.address, saved.profile.server)
        assertEquals("loaded-subscription", saved.profile.subscriptionId)
        assertNull(saved.raw)
        assertTrue(events.any { it is BaseEvent.Finish && it.result is BaseResult.Saved })
        assertEquals("second save", editor.snapshot().form.remarks)
    }

    @Test
    fun validationReadsTheActionSnapshotInsteadOfTheLoadedInitialForm() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher).apply {
            loadBlock = { ServerEditData(profile = validForm.toProfileItem(profile(), EConfigType.VMESS)) }
        }
        val vm = viewModel(repo)
        runCurrent()

        vm.onAction(ServerAction.Save(validForm.copy(address = "", password = ""), ""))
        runCurrent()

        assertTrue(repo.saved.isEmpty())
        assertEquals(setOf(ServerField.ADDRESS, ServerField.PASSWORD), vm.uiState.value.fieldErrors.keys)
        assertEquals(validForm.address, vm.uiState.value.initialForm?.address)

        vm.onAction(ServerAction.Save(validForm.copy(address = "new.example.com"), ""))
        runCurrent()
        assertEquals("new.example.com", repo.saved.single().profile.server)
        assertTrue(vm.uiState.value.fieldErrors.isEmpty())
        assertEquals(validForm.address, vm.uiState.value.initialForm?.address)
    }

    @Test
    fun customSaveUsesCurrentRemarksAndRawSnapshotWithoutDebounce() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher).apply {
            loadBlock = { ServerEditData(profile = profile(EConfigType.CUSTOM), rawContent = "old raw") }
        }
        val vm = viewModel(repo, EConfigType.CUSTOM)
        runCurrent()
        val editor = ServerEditorState().apply {
            initialize(checkNotNull(vm.uiState.value.initialForm), vm.uiState.value.initialRawContent)
        }
        editor[ServerField.REMARKS].setTextAndPlaceCursorAtEnd("latest custom")
        editor.rawContent.setTextAndPlaceCursorAtEnd("{\"latest\":true}")
        val snapshot = editor.snapshot()

        vm.onAction(ServerAction.Save(snapshot.form, snapshot.rawContent))
        editor.rawContent.setTextAndPlaceCursorAtEnd("edited after save")
        runCurrent()

        assertEquals(listOf("{\"latest\":true}"), repo.parsed)
        assertEquals("{\"latest\":true}", repo.saved.single().raw)
        assertEquals("latest custom", repo.saved.single().profile.remarks)
        assertEquals("old raw", vm.uiState.value.initialRawContent)
    }

    @Test
    fun policyGroupSaveUsesSnapshotIdsFlagsAndFilter() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher).apply {
            loadBlock = { ServerEditData(profile = profile(EConfigType.POLICYGROUP)) }
        }
        val vm = viewModel(repo, EConfigType.POLICYGROUP)
        runCurrent()
        val form = validForm.copy(
            groupType = "1",
            groupSubId = "subscription-id",
            groupFilter = " latest filter ",
            groupTestOutbounds = false,
            groupFallbackTag = "fallback-tag"
        )

        vm.onAction(ServerAction.Save(form, ""))
        runCurrent()

        val saved = repo.saved.single().profile
        assertEquals("1", saved.policyGroupType)
        assertEquals("subscription-id", saved.policyGroupSubscriptionId)
        assertEquals("latest filter", saved.policyGroupFilter)
        assertEquals(false, saved.policyGroupTestOutbounds)
        assertEquals("fallback-tag", saved.policyGroupFallbackTag)
    }

    @Test
    fun proxyChainSaveUsesCurrentDraftOrderAndValidatesItsMembers() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher).apply {
            loadBlock = { ServerEditData(profile = profile(EConfigType.PROXYCHAIN)) }
        }
        val vm = viewModel(repo, EConfigType.PROXYCHAIN)
        runCurrent()
        val editor = ServerEditorState().apply {
            initialize(validForm.copy(chainMembers = listOf(ChainMember("a", "one"), ChainMember("b", "two"))), "")
        }
        editor.chainMembers.last().remarks.setTextAndPlaceCursorAtEnd("latest two")
        editor.moveChainMember("b", "a")
        val snapshot = editor.snapshot()

        vm.onAction(ServerAction.Save(snapshot.form, snapshot.rawContent))
        editor.removeChainMember("b")
        runCurrent()

        assertEquals(listOf("latest two", "one"), repo.lookedUp)
        assertEquals("latest two,one", repo.saved.single().profile.proxyChainProfiles)
        assertEquals("latest two -> one", repo.saved.single().profile.description)
    }

    @Test
    fun certificateRequestUsesSnapshotAndItsFieldResultPreservesNewerEdits() = runTest(dispatcher) {
        val result = CompletableDeferred<String?>()
        val repo = FakeRepository(dispatcher).apply { certificateBlock = { result.await() } }
        val vm = viewModel(repo)
        runCurrent()
        val initial = vm.uiState.value.initialForm
        val editor = ServerEditorState().apply { initialize(validForm.copy(streamSecurity = AppConfig.TLS), "") }
        val events = mutableListOf<ServerEvent.CertificateFetched>()
        backgroundScope.launch {
            vm.events.collect { event ->
                if (event is ServerEvent.CertificateFetched) {
                    events += event
                    editor.applyCertificate(event.pinnedCA256)
                }
            }
        }
        val snapshot = editor.snapshot()

        vm.onAction(ServerAction.FetchCertificate(snapshot.form))
        runCurrent()
        assertTrue(vm.uiState.value.isFetchingCert)
        assertEquals(snapshot.form.address, repo.certificateRequests.single().server)
        assertEquals(AppConfig.TLS, repo.certificateRequests.single().security)
        editor[ServerField.ADDRESS].setTextAndPlaceCursorAtEnd("address edited during request")
        editor[ServerField.PASSWORD].setTextAndPlaceCursorAtEnd("new password")
        editor.allowInsecure = true
        editor.addChainMember()
        val beforeResponse = editor.snapshot()
        result.complete("sha256-result")
        runCurrent()

        assertEquals(listOf(ServerEvent.CertificateFetched("sha256-result")), events)
        assertEquals(beforeResponse.form.copy(pinnedCA256 = "sha256-result"), editor.snapshot().form)
        assertEquals(initial, vm.uiState.value.initialForm)
        assertFalse(vm.uiState.value.isFetchingCert)
    }

    @Test
    fun certificateValidationUsesCurrentTransportAndAddress() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher)
        val vm = viewModel(repo)
        runCurrent()

        vm.onAction(ServerAction.FetchCertificate(validForm.copy(streamSecurity = "")))
        runCurrent()
        assertTrue(repo.certificateRequests.isEmpty())
        vm.onAction(ServerAction.FetchCertificate(validForm.copy(streamSecurity = AppConfig.TLS, address = "")))
        runCurrent()

        assertTrue(repo.certificateRequests.isEmpty())
        assertTrue(ServerField.ADDRESS in vm.uiState.value.fieldErrors)
        assertFalse(vm.uiState.value.isFetchingCert)
    }

    @Test
    fun missingExistingProfileNeverBecomesReadyOrSavesASnapshot() = runTest(dispatcher) {
        val repo = FakeRepository(dispatcher).apply { loadBlock = { ServerEditData(profile = null) } }
        val vm = viewModel(repo)
        runCurrent()

        vm.onAction(ServerAction.Save(validForm, ""))
        runCurrent()

        assertFalse(vm.uiState.value.isLoaded)
        assertNull(vm.uiState.value.initialForm)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun recreatedViewModelStartsDropdownFiltersFreshWithoutChangingInitialForm() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(AppRoute.EXTRA_GUID to "guid"))
        val repo = FakeRepository(dispatcher)
        val first = ServerEditViewModel(handle, repo).also { store.put("first", it) }
        runCurrent()
        val seed = first.uiState.value.initialForm
        first.slices.onTagQueryChange("tag filter")

        val recreated = ServerEditViewModel(handle, repo).also { store.put("restored", it) }
        runCurrent()

        assertEquals("", recreated.slices.tagQuery.value)
        assertEquals(seed, first.uiState.value.initialForm)
        assertTrue(repo.saved.isEmpty())
    }

    private fun viewModel(repo: FakeRepository, type: EConfigType = EConfigType.VMESS): ServerEditViewModel =
        ServerEditViewModel(
            SavedStateHandle(mapOf(AppRoute.EXTRA_TYPE to type.value, AppRoute.EXTRA_GUID to "guid")),
            repo
        ).also { store.put("server", it) }

    private class FakeRepository(io: CoroutineDispatcher) : ServerRepository(mock(), mock(), mock(), mock(), io) {
        var loadBlock: suspend () -> ServerEditData = { ServerEditData(profile = profile()) }
        var certificateBlock: suspend () -> String? = { "sha256" }
        val saved = mutableListOf<SavedProfile>()
        val parsed = mutableListOf<String>()
        val lookedUp = mutableListOf<String>()
        val certificateRequests = mutableListOf<ProfileItem>()

        override suspend fun loadEdit(guid: String, fallbackType: EConfigType): ServerEditData = loadBlock()

        override suspend fun generateDescription(profile: ProfileItem): String = "description"

        override suspend fun parseCustomConfig(content: String): ProfileItem {
            parsed += content
            return profile(EConfigType.CUSTOM)
        }

        override suspend fun saveProfile(guid: String, profile: ProfileItem, rawContent: String?): String {
            saved += SavedProfile(profile.copy(), rawContent)
            return guid
        }

        override suspend fun buildPolicyGroupDescription(typeIndex: Int, subId: String, filter: String): String =
            "$typeIndex|$subId|$filter"

        override suspend fun findProfileByRemarks(remarks: String): ProfileItem {
            lookedUp += remarks
            return profile()
        }

        override suspend fun fetchCertSha256(profile: ProfileItem): String? {
            certificateRequests += profile.copy()
            return certificateBlock()
        }
    }

    private data class SavedProfile(val profile: ProfileItem, val raw: String?)

    private companion object {
        val validForm = ServerForm(remarks = "snapshot", address = "snapshot.example.com", password = "password")

        fun profile(type: EConfigType = EConfigType.VMESS): ProfileItem = ProfileItem.create(type).apply {
            guid = "guid"
            remarks = "database remarks"
            server = "database.example.com"
        }
    }
}
