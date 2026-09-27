package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestNotification
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class CoreTestService : Service() {

    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    /**
     * One unit of work, tagged with the request that owns it. Removal from [units] is the atomic
     * claim: the winner is the only party allowed to report a finish or a cancel for the unit.
     */
    private class BatchUnit(val requestId: String, val subscriptionId: String) {
        lateinit var worker: RealPingWorkerService
    }

    private val units: MutableSet<BatchUnit> =
        Collections.newSetFromMap(ConcurrentHashMap<BatchUnit, Boolean>())

    /**
     * Requests accepted but still preparing (settings refresh + target lookup), not yet in
     * [units]. Without this a cancel arriving during preparation found nothing to cancel and
     * the batch started anyway.
     */
    private val pendingRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Serializes every claim transition that decides whether the service still has work:
     * "pending -> unit", "cancel pending", "unit -> finalizing" and the idle check in
     * [stopIfIdle]. They share one lock so the idle check cannot observe a half-finished
     * transition and stop the service early.
     */
    private val claimLock = Any()

    /**
     * Request ids whose finish handler was claimed but has not completed its async tail (final
     * flush + post-processing + terminal notify) yet. Non-empty means "the service still has
     * work": stopping now would kill the final flush and drop buffered results. Removing an id
     * is the atomic claim on the request's terminal message — the party that removes it (the
     * tail itself, or onDestroy) reports the end, so a request can never be both finished and
     * cancelled, and a finishing request is never forgotten by the destroy path.
     */
    private val finalizingRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + io) }
    private val resultWriter by lazy {
        TestResultWriter(dao = PlatformDependencies.profileDao(this), scope = serviceScope)
    }

    /**
     * Latest startId handed to onStartCommand. stopIfIdle() reports it through
     * stopSelfResult(), which refuses to stop when a newer command arrived in the meantime —
     * the previous parameterless stopSelf() raced the main thread's onStartCommand and could
     * kill a start that had just been accepted.
     */
    @Volatile
    private var lastStartId = 0

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    private val cancelAction by lazy {
        val intent = Intent(this, CoreTestService::class.java).putExtra(
            "content",
            TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
        val pendingIntent = PendingIntent.getService(
            this,
            NotificationChannelType.CORE_TEST.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Action.Builder(
            R.drawable.ic_stop_24dp,
            getString(R.string.action_cancel),
            pendingIntent
        ).build()
    }

    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
        // Warm-up only. Each batch start still refreshes explicitly before reading preferences.
        serviceScope.launch {
            if (!StorageBootstrap.awaitReadyOrNull()) return@launch
            runCatching { PlatformDependencies.settingsStore(this@CoreTestService).refresh() }
                .onFailure { LogUtil.e(AppConfig.TAG, "CoreTestService: preference refresh failed", it) }
        }
        resultWriter.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Service death invalidates every request, so each one gets its own cancel reply. */
    override fun onDestroy() {
        LogUtil.i(
            AppConfig.TAG,
            "CoreTestService destroyed, cancelling ${units.size} units, " +
                "${pendingRequests.size} pending, ${finalizingRequests.size} finalizing"
        )
        cancelPending(null)
        cancelUnits(units.toList())
        cancelFinalizing()
        // The final flush must survive serviceScope's cancellation. Launch it undispatched so it
        // starts synchronously, and stop() itself switches to NonCancellable before the write —
        // the previous "launch { stop() }; cancel()" pair could cancel the cleanup coroutine
        // before it ever ran and silently drop the buffered results.
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { resultWriter.stop() }
                .onFailure { LogUtil.e(AppConfig.TAG, "CoreTestService: final result flush failed", it) }
        }
        serviceScope.cancel()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            getString(R.string.title_real_ping_all_server),
            cancelAction
        )
        val message = intent?.serializable<TestServiceMessage>("content")
        if (message == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> handleMeasureStart(message)
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> handleMeasureCancel(message)
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun handleMeasureStart(message: TestServiceMessage) {
        val requestId = message.requestId
        if (requestId.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "CoreTestService rejected a batch start without a request id")
            stopIfIdle()
            return
        }
        LogUtil.i(AppConfig.TAG, "CoreTestService starting request $requestId for ${message.subscriptionId}")

        // Defence against silently shadowing an older batch: still-registered or still-preparing
        // requests are cancelled and reported, never dropped.
        cancelPending(null)
        cancelUnits(units.toList())
        pendingRequests.add(requestId)

        serviceScope.launch {
            // The :tasks bootstrap runs the same gate as the UI process; a batch must not read
            // (or later write) through the DAO while the legacy import is still in flight. On
            // storage failure resolve the request as cancelled instead of testing half-blind.
            // A previous attempt in this process may have failed transiently; retry first.
            StorageBootstrap.retry()
            if (!StorageBootstrap.awaitReadyOrNull()) {
                LogUtil.w(AppConfig.TAG, "CoreTestService: storage not ready; request $requestId cancelled")
                if (pendingRequests.remove(requestId)) sendCanceled(requestId)
                stopIfIdle()
                return@launch
            }

            // The :tasks process snapshot may still be cold or stale (the user just edited the
            // value in the UI process). Reading before this returned the coded default 16.
            // refresh() completes the ready signal in finally, so a failure degrades to
            // defaults instead of hanging.
            runCatching { PlatformDependencies.settingsStore(this@CoreTestService).refresh() }
                .onFailure { LogUtil.e(AppConfig.TAG, "CoreTestService: preference refresh failed", it) }
            val concurrency = SettingsManager.getRealPingConcurrency()

            val dao = PlatformDependencies.profileDao(this@CoreTestService)
            // subscriptionId = '' resolves to the whole visible set, which is what the previous
            // decodeAllServerList() branch produced.
            val guids = message.serverGuids.ifEmpty {
                runCatching { dao.guidsInScope(message.subscriptionId, "") }
                    .onFailure { LogUtil.e(AppConfig.TAG, "CoreTestService: failed to load targets", it) }
                    .getOrDefault(emptyList())
            }

            if (guids.isEmpty()) {
                // Only the party that removes the pending entry may report.
                if (pendingRequests.remove(requestId)) sendCanceled(requestId)
                stopIfIdle()
                return@launch
            }

            val unit = BatchUnit(requestId, message.subscriptionId)
            unit.worker = RealPingWorkerService(
                context = this@CoreTestService,
                profileDao = dao,
                guids = guids,
                concurrency = concurrency,
                onlyTcp = message.onlyTcp,
                onEvent = { event -> handleWorkerEvent(event, unit) }
            )

            val claimed = synchronized(claimLock) {
                if (pendingRequests.remove(requestId)) {
                    units.add(unit)
                    true
                } else {
                    false
                }
            }
            if (!claimed) {
                // Cancelled while preparing; the cancel reply has already been sent.
                LogUtil.i(AppConfig.TAG, "CoreTestService request $requestId cancelled before start")
                stopIfIdle()
                return@launch
            }

            LogUtil.i(
                AppConfig.TAG,
                "CoreTestService request $requestId: ${guids.size} targets, concurrency $concurrency"
            )
            unit.worker.start()
        }
    }

    private fun handleWorkerEvent(event: RealPingEvent, unit: BatchUnit) {
        when (event) {
            is RealPingEvent.Progress -> {
                if (unit !in units) return
                NotificationHelper.updateNotification(
                    channelType = NotificationChannelType.CORE_TEST,
                    context = this,
                    title = getString(R.string.app_name),
                    content = getString(R.string.connection_running_task_left, event.text)
                )
                sendNotify(AppConfig.MSG_MEASURE_CONFIG_NOTIFY, unit.requestId, event.text)
            }

            is RealPingEvent.Result -> {
                // Buffered, not written: one transaction per window instead of one per result.
                resultWriter.record(event.guid, event.delayMillis)
                if (unit !in units) return
                sendNotify(AppConfig.MSG_MEASURE_CONFIG_SUCCESS, unit.requestId, event.guid)
            }

            RealPingEvent.Finish -> {
                // Losing the claim means the unit was already cancelled; do not also finish it.
                if (!claimForFinalize(unit)) return
                serviceScope.launch {
                    try {
                        // Barrier: waits for any windowed flush still in flight, so
                        // post-processing reads every result recorded before this point.
                        resultWriter.flush()
                        applyPostProcessing(unit.subscriptionId)
                        // The destroy path may have claimed the terminal in the meantime; then it
                        // has already reported the request as cancelled.
                        if (claimTerminal(unit.requestId)) {
                            sendNotify(AppConfig.MSG_MEASURE_CONFIG_FINISH, unit.requestId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Either the flush or the post-processing failed. Results may not have
                        // been persisted, so do not report success; the request ends as
                        // cancelled so the UI does not stay in "testing".
                        LogUtil.e(AppConfig.TAG, "CoreTestService: finalizing ${unit.requestId} failed", e)
                        if (claimTerminal(unit.requestId)) {
                            runCatching { sendCanceled(unit.requestId) }
                        }
                    } finally {
                        releaseFinalize(unit.requestId)
                        stopIfIdle()
                    }
                }
            }
        }
    }

    private fun handleMeasureCancel(message: TestServiceMessage) {
        val requestId = message.requestId.ifEmpty { null }
        // Pending first, then snapshot units: together with claimLock this guarantees a request
        // is either cancelled while pending or found in units, never missed in between.
        val pendingCount = cancelPending(requestId)
        val targets = if (requestId == null) {
            units.toList()
        } else {
            units.filter { it.requestId == requestId }
        }
        LogUtil.i(AppConfig.TAG, "CoreTestService cancelling ${targets.size} units, $pendingCount pending")
        cancelUnits(targets)
        stopIfIdle()
    }

    /** @param requestId null cancels every pending request. @return number of cancelled requests. */
    private fun cancelPending(requestId: String?): Int {
        val claimed = synchronized(claimLock) {
            val ids = if (requestId == null) pendingRequests.toList() else listOf(requestId)
            ids.filter { pendingRequests.remove(it) }
        }
        claimed.forEach { id ->
            runCatching { sendCanceled(id) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to report cancel of pending $id", it) }
        }
        return claimed.size
    }

    /** Cancels each unit in isolation, so one failure cannot block the remaining requests. */
    private fun cancelUnits(targets: List<BatchUnit>) {
        targets.forEach { unit ->
            if (!units.remove(unit)) return@forEach
            runCatching { unit.worker.cancel() }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to cancel worker of ${unit.requestId}", it) }
            runCatching { sendCanceled(unit.requestId) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to report cancel of ${unit.requestId}", it) }
        }
    }

    /**
     * Claims the unit for finalization. The removal from [units] and the registration in
     * [finalizingRequests] run under [claimLock] — the same lock the idle check takes — so a
     * cancel on another thread cannot observe the gap between the two and stop the service
     * before the finish handler ever ran.
     */
    private fun claimForFinalize(unit: BatchUnit): Boolean = synchronized(claimLock) {
        if (units.remove(unit)) {
            finalizingRequests.add(unit.requestId)
            true
        } else {
            false
        }
    }

    /** @return true when the caller won the terminal claim and must report the request's end. */
    private fun claimTerminal(requestId: String): Boolean =
        synchronized(claimLock) { finalizingRequests.remove(requestId) }

    /** Idempotent release for the tail's finally; a claimed terminal is already removed. */
    private fun releaseFinalize(requestId: String) {
        synchronized(claimLock) { finalizingRequests.remove(requestId) }
    }

    /**
     * Reports still-finalizing requests as cancelled. Called from onDestroy before the scope is
     * cancelled: without it, cancelling the scope would kill the tail before its own terminal
     * message and the request would dangle on the UI side.
     */
    private fun cancelFinalizing() {
        val claimed = synchronized(claimLock) {
            finalizingRequests.toList().also { finalizingRequests.clear() }
        }
        claimed.forEach { requestId ->
            runCatching { sendCanceled(requestId) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to report cancel of finalizing $requestId", it) }
        }
    }

    private suspend fun applyPostProcessing(subscriptionId: String) {
        if (subscriptionId.isEmpty()) return
        if (Prefs.bool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST)) {
            AngConfigManager.removeInvalidServer(subscriptionId)
        }
        if (Prefs.bool(AppConfig.PREF_AUTO_SORT_AFTER_TEST)) {
            AngConfigManager.sortByTestResultsForSub(subscriptionId)
        }
    }

    private fun sendCanceled(requestId: String) =
        sendNotify(AppConfig.MSG_MEASURE_CONFIG_CANCELED, requestId)

    private fun sendNotify(key: Int, requestId: String, payload: String = "") =
        MessageHelper.sendMsg2UI(this, key, TestNotification(requestId, payload))

    /** A request still preparing or still finalizing counts as work: stopping now would kill it. */
    private fun stopIfIdle() {
        val idle = synchronized(claimLock) {
            units.isEmpty() && pendingRequests.isEmpty() && finalizingRequests.isEmpty()
        }
        if (!idle) return
        NotificationHelper.stopForeground(this)
        // stopSelfResult only stops when lastStartId is still the newest start command, so a
        // command that raced in after the idle check above is not killed with the service.
        if (!stopSelfResult(lastStartId)) {
            LogUtil.i(AppConfig.TAG, "CoreTestService: start id $lastStartId is stale; staying up")
        }
    }
}
