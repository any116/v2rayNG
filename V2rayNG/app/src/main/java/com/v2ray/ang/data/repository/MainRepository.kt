package com.v2ray.ang.data.repository

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.ServerRowProjection
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.SubscriptionDao
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.ConnectionTestResponse
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.ServerRowItem
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestNotification
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.normalizeLike
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MainServiceEvent {
    data object StateRunning : MainServiceEvent
    data object StateNotRunning : MainServiceEvent
    data object StateStartSuccess : MainServiceEvent
    data class StateStartFailure(val errorMessage: String) : MainServiceEvent
    data object StateStopSuccess : MainServiceEvent
    /** Non-fatal warning from the service. Currently only allow-insecure without a pin. */
    data object WarnInsecure : MainServiceEvent
    data class MeasureDelayResult(val requestId: String, val result: ConnectionTestResult) : MainServiceEvent
    data class MeasureDelayCanceled(val requestId: String) : MainServiceEvent
    data class MeasureConfigSuccess(val requestId: String) : MainServiceEvent
    data class MeasureConfigNotify(val requestId: String, val progress: String) : MainServiceEvent
    data class MeasureConfigFinish(val requestId: String) : MainServiceEvent
    data class MeasureConfigCanceled(val requestId: String) : MainServiceEvent
}

/**
 * @Singleton because it owns a process-wide broadcast registration and a shared event flow. An
 * unscoped instance would register a second receiver every time it is injected and leak the
 * first one, and the extra buffer would split service events between two subscribers.
 */
@Singleton
open class MainRepository @Inject constructor(
    private val app: Application,
    private val profileDao: ProfileDao,
    private val subscriptionDao: SubscriptionDao,
    private val settings: SettingsStore,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    private val receiverRegistered = AtomicBoolean(false)

    private val _serviceEvents = MutableSharedFlow<MainServiceEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    open val serviceEvents: SharedFlow<MainServiceEvent> = _serviceEvents.asSharedFlow()

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val data = intent ?: return
            val content = data.getStringExtra("content")
            val event = when (data.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> MainServiceEvent.StateRunning
                AppConfig.MSG_STATE_NOT_RUNNING -> MainServiceEvent.StateNotRunning
                AppConfig.MSG_STATE_START_SUCCESS -> MainServiceEvent.StateStartSuccess
                AppConfig.MSG_STATE_START_FAILURE ->
                    MainServiceEvent.StateStartFailure(content.orEmpty())
                AppConfig.MSG_STATE_STOP_SUCCESS -> MainServiceEvent.StateStopSuccess
                AppConfig.MSG_WARN_INSECURE -> MainServiceEvent.WarnInsecure
                AppConfig.MSG_MEASURE_DELAY_RESULT -> data
                    .serializable<ConnectionTestResponse>("content")
                    ?.let { MainServiceEvent.MeasureDelayResult(it.requestId, it.result) }
                AppConfig.MSG_MEASURE_DELAY_CANCELED -> content
                    ?.let { MainServiceEvent.MeasureDelayCanceled(it) }
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> data
                    .serializable<TestNotification>("content")
                    ?.let { MainServiceEvent.MeasureConfigSuccess(it.requestId) }
                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> data
                    .serializable<TestNotification>("content")
                    ?.let { MainServiceEvent.MeasureConfigNotify(it.requestId, it.payload) }
                AppConfig.MSG_MEASURE_CONFIG_FINISH -> data
                    .serializable<TestNotification>("content")
                    ?.let { MainServiceEvent.MeasureConfigFinish(it.requestId) }
                AppConfig.MSG_MEASURE_CONFIG_CANCELED -> data
                    .serializable<TestNotification>("content")
                    ?.let { MainServiceEvent.MeasureConfigCanceled(it.requestId) }
                else -> null
            }
            event?.let { _serviceEvents.tryEmit(it) }
        }
    }

    init {
        if (receiverRegistered.compareAndSet(false, true)) {
            ContextCompat.registerReceiver(
                app, serviceReceiver,
                IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
                Utils.receiverFlags()
            )
            MessageHelper.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
        }
    }

    /** Suspends until the settings snapshot is ready for the calling process. */
    open suspend fun awaitReady() = settings.awaitReady()

    // ---- Preferences: snapshot reads, stay synchronous ----

    open fun selectedGroupId(): String =
        settings.string(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()

    open suspend fun setSelectedGroupId(id: String) {
        settings.putString(AppConfig.CACHE_SUBSCRIPTION_ID, id)
    }

    open fun selectedGuid(): String? =
        settings.string(SettingsStore.KEY_SELECTED_SERVER).nullIfBlank()

    open suspend fun setSelectedGuid(guid: String) {
        settings.putString(SettingsStore.KEY_SELECTED_SERVER, guid)
    }

    open fun confirmRemove(): Boolean = settings.bool(AppConfig.PREF_CONFIRM_REMOVE, false)
    open fun doubleColumnDisplay(): Boolean = settings.bool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    open fun isVpnMode(): Boolean = SettingsManager.isVpnMode()
    open fun isProxySharing(): Boolean = settings.bool(AppConfig.PREF_PROXY_SHARING)
    open fun promotionUrl(): String = "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}"

    // ---- Groups ----

    /** Bumped when a preference that shapes the tab list changes; the subscription table drives the rest. */
    private val groupRefresh = MutableStateFlow(0)

    open fun refreshGroups() {
        groupRefresh.value += 1
    }

    open fun observeGroups(): Flow<List<GroupMapItem>> =
        combine(subscriptionDao.observeAll(), groupRefresh) { subs, _ ->
            buildList {
                if (settings.bool(AppConfig.PREF_GROUP_ALL_DISPLAY)) {
                    add(GroupMapItem(id = "", remarks = ""))
                }
                subs.forEach { add(GroupMapItem(id = it.guid, remarks = it.remarks)) }
            }
        }.flowIO()

    /** Includes the "All" bucket and empty subscriptions (count 0). */
    open fun observeCounts(query: String): Flow<Map<String, Int>> {
        val escaped = query.trim().normalizeLike()
        return combine(
            profileDao.observeCounts(escaped),
            profileDao.observeTotalCount(escaped),
            groupRefresh
        ) { perGroup, total, _ ->
            buildMap {
                if (settings.bool(AppConfig.PREF_GROUP_ALL_DISPLAY)) put("", total)
                perGroup.forEach { put(it.groupId, it.count) }
            }
        }.flowIO()
    }

    // ---- Paging ----

    /**
     * The only list entry point. Display strings are assembled here, not in Composables.
     * enablePlaceholders = true so LazyColumn knows the full itemCount and
     * LocateSelectedServer can scroll into a not-yet-loaded region.
     */
    open fun serverPager(groupId: String, query: String): Flow<PagingData<ServerRowItem>> {
        val escaped = query.trim().normalizeLike()
        val showBadge = groupId.isEmpty()
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                enablePlaceholders = true,
                jumpThreshold = JUMP_THRESHOLD
            ),
            pagingSourceFactory = { profileDao.pageServers(groupId, escaped) }
        ).flow.map { paging -> paging.map { row -> row.toRowItem(showBadge) } }
    }

    private fun ServerRowProjection.toRowItem(showBadge: Boolean) = ServerRowItem(
        guid = guid,
        remarks = remarks,
        statistics = description.nullIfBlank()
            ?: AngConfigManager.generateDescription(server, serverPort),
        typeDescription = protocolDescription(this),
        subscriptionBadge = if (showBadge) subscriptionInitial.orEmpty() else "",
        configType = configType,
        testDelayMillis = testDelayMillis
    )

    // ---- Position queries ----

    open suspend fun subscriptionIdOf(guid: String): String =
        withIO { profileDao.subscriptionIdOf(guid).orEmpty() }

    open suspend fun indexOf(groupId: String, query: String, guid: String): Int? =
        withIO { profileDao.indexOf(groupId, query.trim().normalizeLike(), guid) }

    open suspend fun guidsInScope(groupId: String, query: String): List<String> =
        withIO { profileDao.guidsInScope(groupId, query.trim().normalizeLike()) }

    /** Single-row UPDATE inside a transaction; no job chain needed on the caller side. */
    open suspend fun moveServer(groupId: String, query: String, movedGuid: String, toIndex: Int) =
        withIO {
            profileDao.moveProfileToIndex(groupId, query.trim().normalizeLike(), movedGuid, toIndex)
            syncSelectedSnapshot()
        }

    // ---- Mutations ----

    open suspend fun removeServers(guids: List<String>): Int = withIO {
        profileDao.deleteProfiles(guids).also { syncSelectedSnapshot() }
    }

    open suspend fun removeAllServers(): Int = withIO {
        profileDao.deleteAll().also { syncSelectedSnapshot() }
    }

    open suspend fun removeDuplicateServers(groupId: String, query: String): Int = withIO {
        profileDao.backfillDedupeKeys(groupId)
        val complex = EConfigType.entries.filter { it.isComplexType() }.map { it.value }
        val targets = profileDao.duplicateGuids(groupId, query.trim().normalizeLike(), complex)
        profileDao.deleteProfiles(targets).also { syncSelectedSnapshot() }
    }

    open suspend fun removeInvalidServers(groupId: String, query: String): Int = withIO {
        val targets = profileDao.invalidGuids(groupId, query.trim().normalizeLike())
        profileDao.deleteProfiles(targets).also { syncSelectedSnapshot() }
    }

    open suspend fun sortByTestResults(groupIds: List<String>) = withIO {
        val targets = groupIds.ifEmpty { subscriptionDao.allGuids() }
        targets.forEach { profileDao.sortByDelay(it) }
    }

    open suspend fun clearTestResults(guids: List<String>) = withIO {
        guids.chunked(ProfileDao.SQLITE_VAR_LIMIT).forEach { profileDao.clearDelays(it) }
    }

    /** ProfileDao repoints SELECTED_SERVER inside its own transactions; realign this process. */
    private suspend fun syncSelectedSnapshot() {
        settings.poke(SettingsStore.KEY_SELECTED_SERVER, profileDao.selectedGuid())
    }

    // ---- Import / subscription ----

    open suspend fun importBatchConfig(text: String, groupId: String): Pair<Int, Int> =
        withIO { AngConfigManager.importBatchConfig(text, groupId, true) }

    open suspend fun updateSubscriptions(groupId: String): SubscriptionUpdateResult = withIO {
        if (groupId.isEmpty()) {
            AngConfigManager.updateConfigViaSubAll()
        } else {
            val item = subscriptionDao.find(groupId) ?: return@withIO SubscriptionUpdateResult()
            AngConfigManager.updateConfigViaSub(item)
        }
    }

    // ---- Share / clipboard ----

    open suspend fun exportToClipboard(guids: List<String>): Int =
        withIO { AngConfigManager.shareNonCustomConfigsToClipboard(app, guids) }

    open suspend fun share2QRCode(guid: String): Bitmap? =
        withIO { AngConfigManager.share2QRCode(guid) }

    open suspend fun share2Clipboard(guid: String): Boolean =
        withIO { AngConfigManager.share2Clipboard(app, guid) == 0 }

    open suspend fun shareFullContent(guid: String): Boolean =
        withIO { AngConfigManager.shareFullContent2Clipboard(app, guid) == 0 }

    open suspend fun readClipboard(): String = withIO {
        runCatching { Utils.getClipboard(app) }
            .onFailure { LogUtil.e(AppConfig.TAG, "Failed to read clipboard", it) }
            .getOrDefault("")
    }

    open suspend fun readTextFromUri(uri: Uri): String? = withIO {
        runCatching { app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
            .onFailure { LogUtil.e(AppConfig.TAG, "Failed to read content from URI", it) }
            .getOrNull()
    }

    // ---- Test service IPC ----

    open suspend fun startBatchTest(
        requestId: String,
        groupId: String,
        guids: List<String>,
        onlyTcp: Boolean
    ) = withIO {
        MessageHelper.sendMsg2TestService(
            app,
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG_START,
                requestId = requestId,
                subscriptionId = groupId,
                serverGuids = guids,
                onlyTcp = onlyTcp
            )
        )
    }

    /** An empty [requestId] cancels every batch request the service still owns. */
    open suspend fun cancelBatchTest(requestId: String) = withIO { sendCancelBatchTest(requestId) }

    private fun sendCancelBatchTest(requestId: String) =
        MessageHelper.sendMsg2TestService(
            app,
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL, requestId = requestId)
        )

    open suspend fun testCurrentServer(requestId: String) = withIO {
        MessageHelper.sendMsg2ServiceForResult(app, AppConfig.MSG_MEASURE_DELAY, requestId) { handled ->
            if (!handled) emitDelayCanceled(requestId)
        }
    }

    private fun emitDelayCanceled(requestId: String) {
        _serviceEvents.tryEmit(MainServiceEvent.MeasureDelayCanceled(requestId))
    }

    open suspend fun prepare() = withIO {
        SettingsManager.initAssets(app, app.assets)
        SubscriptionUpdater.sync(app)
    }

    private companion object {
        const val PAGE_SIZE = 40
        const val INITIAL_LOAD_SIZE = 80
        const val PREFETCH_DISTANCE = 20
        const val JUMP_THRESHOLD = 240
    }
}

private fun protocolDescription(row: ServerRowProjection): String {
    if (row.configType.isComplexType()) return row.configType.name
    val parts = mutableListOf(row.configType.name)
    row.network?.let { net ->
        if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) parts += net
    }
    row.security?.let { sec ->
        if (sec.isNotBlank()) {
            parts += if (row.insecure == true && sec.equals("tls", ignoreCase = true)) "$sec insecure" else sec
        }
    }
    return parts.joinToString(" / ")
}
