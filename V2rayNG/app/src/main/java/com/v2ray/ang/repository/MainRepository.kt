package com.v2ray.ang.repository

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.ConnectionTestResponse
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.ServerRowItem
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestNotification
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

private const val LOAD_CHUNK_SIZE = 60

sealed interface MainServiceEvent {
    data object StateRunning : MainServiceEvent
    data object StateNotRunning : MainServiceEvent
    data object StateStartSuccess : MainServiceEvent
    data class StateStartFailure(val errorMessage: String) : MainServiceEvent
    data object StateStopSuccess : MainServiceEvent
    data class MeasureDelayResult(val requestId: String, val result: ConnectionTestResult) : MainServiceEvent
    data class MeasureDelayCanceled(val requestId: String) : MainServiceEvent
    data class MeasureConfigSuccess(val requestId: String) : MainServiceEvent
    data class MeasureConfigNotify(val requestId: String, val progress: String) : MainServiceEvent
    data class MeasureConfigFinish(val requestId: String) : MainServiceEvent
    data class MeasureConfigCanceled(val requestId: String) : MainServiceEvent
}

open class MainRepository @Inject constructor(
    private val app: Application,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io), Closeable {

    private val closed = AtomicBoolean(false)

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
        ContextCompat.registerReceiver(
            app, serviceReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags()
        )
        MessageHelper.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { sendCancelBatchTest("") }
            .onFailure { LogUtil.e(AppConfig.TAG, "Failed to cancel batch test on close", it) }
        runCatching { MessageHelper.sendMsg2Service(app, AppConfig.MSG_UNREGISTER_CLIENT, "") }
            .onFailure { LogUtil.e(AppConfig.TAG, "Failed to unregister service client", it) }
        runCatching { app.unregisterReceiver(serviceReceiver) }
            .onFailure { LogUtil.e(AppConfig.TAG, "Failed to unregister main service receiver", it) }
    }

    open fun selectedGroupId(): String =
        MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()

    open suspend fun setSelectedGroupId(id: String) = withIO {
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, id)
        Unit
    }

    open fun selectedGuid(): String? = MmkvManager.getSelectServer()

    open suspend fun setSelectedGuid(guid: String) = withIO { MmkvManager.setSelectServer(guid) }

    open fun confirmRemove(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE, false)
    open fun doubleColumnDisplay(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    open fun isVpnMode(): Boolean = SettingsManager.isVpnMode()
    open fun isProxySharing(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
    open fun promotionUrl(): String = "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}"

    private fun isGroupAllDisplayEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)

    private val cache = mutableMapOf<String, List<ServerRowItem>>()
    private val cacheMutex = Mutex()
    private val loadMutexes = ConcurrentHashMap<String, Mutex>()
    private val cacheEpoch = AtomicLong(0L)

    open suspend fun loadGroups(): List<GroupMapItem> = withIO {
        val groups = buildList {
            if (isGroupAllDisplayEnabled()) {
                add(GroupMapItem(id = "", remarks = ""))
            }
            MmkvManager.decodeSubscriptions().forEach {
                add(GroupMapItem(id = it.guid, remarks = it.subscription.remarks))
            }
        }
        val validIds = groups.mapTo(HashSet()) { it.id }
        cacheMutex.withLock { cache.keys.retainAll(validIds) }
        loadMutexes.keys.removeAll { it !in validIds }
        groups
    }

    open suspend fun groupCounts(): Map<String, Int> = withIO {
        buildMap {
            if (isGroupAllDisplayEnabled()) put("", MmkvManager.decodeAllServerList().size)
            MmkvManager.decodeSubsList().forEach {
                put(it, MmkvManager.decodeServerList(it).size)
            }
        }
    }

    open suspend fun subscriptionIdOf(guid: String): String = withIO {
        MmkvManager.decodeServerConfig(guid)?.subscriptionId.orEmpty()
    }

    open suspend fun loadServers(
        groupId: String,
        forceRefresh: Boolean = false,
        onChunk: (suspend (List<ServerRowItem>) -> Unit)? = null
    ): List<ServerRowItem> = withIO {
        loadMutexes.computeIfAbsent(groupId) { Mutex() }.withLock {
            val epoch = cacheEpoch.get()
            if (!forceRefresh) {
                cacheMutex.withLock { cache[groupId] }?.let { return@withLock it }
            }
            val guids = if (groupId.isEmpty()) MmkvManager.decodeAllServerList()
            else MmkvManager.decodeServerList(groupId)

            val badges = if (groupId.isEmpty()) subscriptionInitials() else emptyMap()
            val rows = ArrayList<ServerRowItem>(guids.size)

            guids.forEach { guid ->
                currentCoroutineContext().ensureActive()
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
                val info = MmkvManager.decodeServerAffiliationInfo(guid)
                rows += ServerRowItem(
                    guid = guid,
                    remarks = profile.remarks,
                    statistics = profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile),
                    typeDescription = protocolDescription(profile),
                    subscriptionBadge = badges[profile.subscriptionId].orEmpty(),
                    configType = profile.configType,
                    testDelayMillis = info?.testDelayMillis ?: 0L
                )
                if (onChunk != null && rows.size % LOAD_CHUNK_SIZE == 0) {
                    onChunk(ArrayList(rows))
                }
            }
            currentCoroutineContext().ensureActive()
            val result = rows.toList()
            if (cacheEpoch.get() == epoch) {
                cacheMutex.withLock { cache[groupId] = result }
            }
            result
        }
    }

    open suspend fun refreshDelays(groupId: String): List<ServerRowItem>? = withIO {
        val epoch = cacheEpoch.get()
        val rows = cacheMutex.withLock { cache[groupId] } ?: return@withIO null
        var changed = false
        val updated = rows.map { row ->
            currentCoroutineContext().ensureActive()
            val info = MmkvManager.decodeServerAffiliationInfo(row.guid)
            val millis = info?.testDelayMillis ?: 0L
            if (millis == row.testDelayMillis) row
            else {
                changed = true
                row.copy(testDelayMillis = millis)
            }
        }
        if (!changed) return@withIO null
        if (cacheEpoch.get() != epoch) return@withIO null
        cacheMutex.withLock { cache[groupId] = updated }
        updated
    }

    open suspend fun cachedServers(): Map<String, List<ServerRowItem>> =
        cacheMutex.withLock { cache.toMap() }

    open suspend fun invalidate(groupId: String? = null) {
        cacheEpoch.incrementAndGet()
        cacheMutex.withLock {
            if (groupId == null) cache.clear() else cache.remove(groupId)
        }
    }

    open suspend fun dropFromCache(guids: Collection<String>) {
        if (guids.isEmpty()) return
        cacheMutex.withLock {
            val pruned = cache.mapValues { (_, rows) -> rows.filterNot { it.guid in guids } }
            cache.putAll(pruned)
        }
    }

    open suspend fun saveServerOrder(groupId: String, rows: List<ServerRowItem>) = withIO {
        MmkvManager.encodeServerList(ArrayList(rows.map { it.guid }), groupId)
        cacheMutex.withLock { cache[groupId] = rows }
    }

    open suspend fun allGuids(): List<String> = withIO {
        MmkvManager.decodeAllServerList()
    }

    private fun subscriptionInitials(): Map<String, String> =
        MmkvManager.decodeSubscriptions().associate { sub ->
            sub.guid to sub.subscription.remarks.firstOrNull()?.uppercase().orEmpty()
        }

    open suspend fun removeServers(guids: List<String>): Int = withIO {
        if (guids.isEmpty()) return@withIO 0
        guids.groupBy { MmkvManager.decodeServerConfig(it)?.subscriptionId.orEmpty() }
            .forEach { (subscriptionId, ids) ->
                currentCoroutineContext().ensureActive()
                MmkvManager.removeServers(ids, subscriptionId)
            }
        dropFromCache(guids)
        guids.size
    }

    open suspend fun removeAllServers(): Int = withIO {
        val count = MmkvManager.removeAllServer()
        invalidate()
        count
    }

    open suspend fun removeDuplicateServers(guids: List<String>): Int = withIO {
        val seen = HashSet<ProfileItem>()
        val duplicates = guids.filter { guid ->
            currentCoroutineContext().ensureActive()
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@filter false
            !profile.configType.isComplexType() && !seen.add(profile.duplicateIdentity())
        }
        removeServers(duplicates)
    }

    open suspend fun removeInvalidServers(guids: List<String>?): Int = withIO {
        val candidates = guids ?: MmkvManager.decodeAllServerList()
        val invalid = candidates.filter { guid ->
            currentCoroutineContext().ensureActive()
            (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L) < 0L
        }
        removeServers(invalid)
    }

    open suspend fun sortByTestResults(groupIds: List<String>) = withIO {
        val targets = groupIds.ifEmpty { MmkvManager.decodeSubsList() }
        targets.forEach { AngConfigManager.sortByTestResultsForSub(it) }
        invalidate()
    }

    open suspend fun importBatchConfig(text: String, groupId: String): Pair<Int, Int> = withIO {
        AngConfigManager.importBatchConfig(text, groupId, true).also { invalidate() }
    }

    open suspend fun updateSubscriptions(groupId: String): SubscriptionUpdateResult = withIO {
        val result = if (groupId.isEmpty()) {
            AngConfigManager.updateConfigViaSubAll()
        } else {
            val item = MmkvManager.decodeSubscription(groupId)
                ?: return@withIO SubscriptionUpdateResult()
            AngConfigManager.updateConfigViaSub(SubscriptionCache(groupId, item))
        }
        if (result.configCount > 0) invalidate()
        result
    }

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
    open suspend fun cancelBatchTest(requestId: String) = withIO {
        sendCancelBatchTest(requestId)
    }

    private fun sendCancelBatchTest(requestId: String) =
        MessageHelper.sendMsg2TestService(
            app,
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG_CANCEL,
                requestId = requestId
            )
        )

    /**
     * Sends one current-server test request. The ordered broadcast tells whether a daemon took it;
     * an unhandled request is closed by the sender through the same cancel path the daemon uses.
     */
    open suspend fun testCurrentServer(requestId: String) = withIO {
        MessageHelper.sendMsg2ServiceForResult(app, AppConfig.MSG_MEASURE_DELAY, requestId) { handled ->
            if (!handled) emitDelayCanceled(requestId)
        }
    }

    private fun emitDelayCanceled(requestId: String) {
        if (closed.get()) return
        _serviceEvents.tryEmit(MainServiceEvent.MeasureDelayCanceled(requestId))
    }

    open suspend fun clearTestResults(guids: List<String>) =
        withIO { MmkvManager.clearAllTestDelayResults(guids) }

    open suspend fun prepare() = withIO {
        SettingsManager.initAssets(app, app.assets)
        SubscriptionUpdater.sync(app)
    }
}

private fun protocolDescription(profile: ProfileItem): String {
    if (profile.configType.isComplexType()) return profile.configType.name
    val parts = mutableListOf(profile.configType.name)
    profile.network?.let { net ->
        if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) parts += net
    }
    profile.security?.let { sec ->
        if (sec.isNotBlank()) {
            parts += if (profile.insecure == true && sec.equals("tls", ignoreCase = true)) "$sec insecure" else sec
        }
    }
    return parts.joinToString(" / ")
}
