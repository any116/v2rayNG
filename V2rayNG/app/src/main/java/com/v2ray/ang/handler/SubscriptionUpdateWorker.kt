package com.v2ray.ang.handler

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.data.dao.ProfileDao
import com.v2ray.ang.data.dao.SubscriptionDao
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.service.TestResultWriter
import com.v2ray.ang.util.LogUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes one or more subscription updates in WorkManager's :bg process.
 *
 * This used to be a short trigger which started a separate foreground Service in :tasks. Keeping
 * the complete operation here makes WorkManager own the actual result, cancellation and retry
 * boundary. A periodic request contains one subscription id; manual background requests may
 * contain several ids.
 */
@HiltWorker
class SubscriptionUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    private val notificationKey = params.id.toString()
    private val notificationId = lazy { NotificationHelper.allocateNotificationId(notificationKey) }

    override suspend fun doWork(): Result {
        val subIds = inputSubIds()
        if (subIds.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "SubscriptionUpdateWorker: no subscription ids")
            return Result.success()
        }

        return try {
            try {
                setForeground(createForegroundInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "SubscriptionUpdateWorker: setForeground rejected", e)
                return retryOrFail("foreground setup failed")
            }
            withContext(io) { execute(subIds) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "SubscriptionUpdateWorker failed", e)
            retryOrFail("unexpected error")
        } finally {
            // WorkManager stops its foreground service separately, but the helper also owns the
            // cached Builder used by progress updates and must release it explicitly.
            if (notificationId.isInitialized()) {
                try {
                    NotificationHelper.cancel(
                        channelType = NotificationChannelType.SUBSCRIPTION_UPDATE,
                        context = applicationContext,
                        notificationId = notificationId.value,
                    )
                } finally {
                    NotificationHelper.releaseNotificationId(notificationKey)
                }
            }
        }
    }

    private suspend fun execute(subIds: List<String>): Result {
        StorageBootstrap.retry()
        if (!StorageBootstrap.awaitReadyOrNull()) {
            LogUtil.w(AppConfig.TAG, "SubscriptionUpdateWorker: storage not ready")
            return retryOrFail("storage not ready")
        }

        // SettingsStore is process-local. Refresh after the :bg bootstrap so a manual change made
        // in the UI process is visible before proxy, testing and update preferences are read.
        val settings = PlatformDependencies.settingsStore(applicationContext)
        settings.refresh()

        val subscriptionDao = PlatformDependencies.subscriptionDao(applicationContext)
        val profileDao = PlatformDependencies.profileDao(applicationContext)
        val forced = inputData.getBoolean(KEY_FORCED, true)
        val rescheduleAfterSuccess = inputData.getBoolean(KEY_RESCHEDULE_AFTER_SUCCESS, false)
        val autoTest = Prefs.bool(AppConfig.PREF_AUTO_TEST_AFTER_UPDATE_SUBSCRIPTION, false)
        val writerScope = if (autoTest) CoroutineScope(SupervisorJob() + io) else null
        val resultWriter = writerScope?.let { scope ->
            TestResultWriter(dao = profileDao, scope = scope).also { it.start() }
        }
        val updatedSubIds = mutableListOf<String>()
        val retryableSubIds = mutableListOf<String>()
        var terminalFailure = false

        try {
            SubscriptionUpdateExecutionGate.batch.withPermit {
                subIds.distinct().forEach { subId ->
                    if (terminalFailure) return@forEach
                    SubscriptionUpdateExecutionGate.forSubscription(subId) {
                        val outcome = updateSingle(
                            subId = subId,
                            forced = forced,
                            autoTest = autoTest,
                            subscriptionDao = subscriptionDao,
                            profileDao = profileDao,
                            resultWriter = resultWriter,
                        )
                        if (outcome.updated) updatedSubIds += subId
                        if (outcome.retryable) retryableSubIds += subId
                        if (outcome.terminalFailure) terminalFailure = true
                    }
                }
            }

            if (terminalFailure) {
                return Result.failure()
            }

            if (retryableSubIds.isNotEmpty()) {
                LogUtil.w(
                    AppConfig.TAG,
                    "SubscriptionUpdateWorker: retryable subscriptions=${retryableSubIds.distinct().joinToString()}",
                )
                return retryOrFail("subscription update failed")
            }

            if (rescheduleAfterSuccess) {
                var rescheduleFailed = false
                updatedSubIds.forEach { subId ->
                    // AngConfigManager.updateConfigViaSub() already touches lastUpdated after a
                    // successful import. Only rebuild the periodic spec here.
                    if (!SubscriptionUpdater.reschedule(applicationContext, subId)) {
                        rescheduleFailed = true
                    }
                }
                if (rescheduleFailed) {
                    return retryOrFail("periodic reschedule failed")
                }
            }
            return Result.success()
        } finally {
            try {
                if (resultWriter != null) {
                    resultWriter.stop()
                }
            } finally {
                writerScope?.cancel()
            }
        }
    }

    private suspend fun updateSingle(
        subId: String,
        forced: Boolean,
        autoTest: Boolean,
        subscriptionDao: SubscriptionDao,
        profileDao: ProfileDao,
        resultWriter: TestResultWriter?,
    ): UpdateOutcome {
        val subItem = subscriptionDao.find(subId)
        if (subItem == null) {
            LogUtil.w(AppConfig.TAG, "SubscriptionUpdateWorker: subscription not found: $subId")
            return UpdateOutcome(updated = false)
        }
        if (!subItem.enabled || subItem.url.isEmpty()) {
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: skipping disabled/empty subscription ${subItem.remarks}")
            return UpdateOutcome(updated = false)
        }

        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: updating ${subItem.remarks}")
        showNotification(
            titleResId = R.string.title_pref_auto_update_subscription,
            content = applicationContext.getString(R.string.subscription_update_updating, subItem.remarks),
        )

        var updated = false
        if (forced || Prefs.bool(AppConfig.PREF_UPDATE_SUBSCRIPTION, false)) {
            val result = AngConfigManager.updateConfigViaSub(subItem)
            if (result.successCount <= 0) {
                LogUtil.w(
                    AppConfig.TAG,
                    "SubscriptionUpdateWorker: ${subItem.remarks} update failed " +
                        "(failures=${result.failureCount}, skipped=${result.skipCount})",
                )
                return UpdateOutcome(
                    updated = false,
                    retryable = result.retryableFailureCount > 0,
                )
            }
            updated = true
        }

        if (autoTest && updated) {
            val writer = resultWriter
            val testResult = if (writer == null) {
                TestPhaseResult.FAILED
            } else {
                testSubscriptionServers(subItem, profileDao, writer)
            }
            if (testResult != TestPhaseResult.SUCCESS) {
                LogUtil.w(
                    AppConfig.TAG,
                    "SubscriptionUpdateWorker: skipping post-processing for ${subItem.remarks}; " +
                        "test results were not persisted",
                )
                if (testResult == TestPhaseResult.NOT_STOPPED) {
                    LogUtil.e(
                        AppConfig.TAG,
                        "SubscriptionUpdateWorker: native test did not stop for ${subItem.remarks}",
                    )
                }
                // The subscription response has already been imported and touched. Testing and
                // cleanup are best-effort post-processing and must not replay that download.
                return UpdateOutcome(
                    updated = updated,
                    terminalFailure = testResult == TestPhaseResult.NOT_STOPPED,
                )
            }

            if (Prefs.bool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: removing invalid servers for ${subItem.remarks}")
                showNotification(
                    titleResId = R.string.title_del_invalid_config,
                    content = subItem.remarks,
                )
                AngConfigManager.removeInvalidServer(subId)
            }
            if (Prefs.bool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: sorting servers for ${subItem.remarks}")
                showNotification(
                    titleResId = R.string.title_sort_by_test_results,
                    content = subItem.remarks,
                )
                AngConfigManager.sortByTestResultsForSub(subId)
            }
        }

        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: finished ${subItem.remarks}")
        return UpdateOutcome(updated = updated)
    }

    private fun createForegroundInfo(): ForegroundInfo = NotificationHelper.foregroundInfo(
        channelType = NotificationChannelType.SUBSCRIPTION_UPDATE,
        context = applicationContext,
        title = applicationContext.getString(R.string.title_pref_auto_update_subscription),
        content = applicationContext.getString(R.string.app_name),
        action = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            applicationContext.getString(android.R.string.cancel),
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        ),
        notificationId = notificationId.value,
    )

    private fun showNotification(titleResId: Int, content: String) {
        NotificationHelper.updateNotification(
            channelType = NotificationChannelType.SUBSCRIPTION_UPDATE,
            context = applicationContext,
            title = applicationContext.getString(titleResId),
            content = content,
            notificationId = notificationId.value,
        )
    }

    private suspend fun testSubscriptionServers(
        sub: SubscriptionItem,
        profileDao: ProfileDao,
        resultWriter: TestResultWriter,
    ): TestPhaseResult {
        val subId = sub.guid
        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: starting test phase for ${sub.remarks}")
        showNotification(
            titleResId = R.string.title_real_ping_all_server,
            content = sub.remarks,
        )

        val guids = profileDao.guidsInGroup(subId)
        if (guids.isEmpty()) {
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: test phase finished for ${sub.remarks}")
            return TestPhaseResult.SUCCESS
        }

        // The :bg process may have a cold or stale settings snapshot after a UI edit. Refresh
        // before resolving the concurrency and delay-test preferences.
        runCatching { PlatformDependencies.settingsStore(applicationContext).refresh() }
            .onFailure { LogUtil.e(AppConfig.TAG, "SubscriptionUpdateWorker: preference refresh failed", it) }
        val concurrency = SettingsManager.getRealPingConcurrency()
        CoreNativeManager.initCoreEnv(applicationContext)

        val deferred = CompletableDeferred<Unit>()
        val acceptResults = AtomicBoolean(true)
        lateinit var worker: RealPingWorkerService
        worker = RealPingWorkerService(
            context = applicationContext,
            profileDao = profileDao,
            guids = guids,
            concurrency = concurrency,
            onEvent = { event ->
                when (event) {
                    is RealPingEvent.Progress -> {
                        showNotification(
                            titleResId = R.string.title_real_ping_all_server,
                            content = applicationContext.getString(
                                R.string.subscription_update_progress,
                                event.text,
                                sub.remarks,
                            ),
                        )
                        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: ${event.text} in ${sub.remarks}")
                    }

                    is RealPingEvent.Result -> if (acceptResults.get()) {
                        resultWriter.record(event.guid, event.delayMillis)
                    }
                    RealPingEvent.Finish -> deferred.complete(Unit)
                }
            },
        )

        var completed = false
        var cancelledCleanly = true
        try {
            worker.start()
            withTimeout(testPhaseTimeoutMs(guids.size)) {
                deferred.await()
            }
            completed = true
        } catch (e: TimeoutCancellationException) {
            LogUtil.w(
                AppConfig.TAG,
                "SubscriptionUpdateWorker: test phase timed out for ${sub.remarks}",
            )
        } finally {
            if (!deferred.isCompleted) {
                acceptResults.set(false)
                cancelledCleanly = withContext(NonCancellable) { worker.cancelAndJoin() }
                if (!cancelledCleanly) {
                    LogUtil.e(
                        AppConfig.TAG,
                        "SubscriptionUpdateWorker: test worker did not stop within cancellation timeout",
                    )
                }
            }
        }

        if (!cancelledCleanly) return TestPhaseResult.NOT_STOPPED

        val flushed = try {
            resultWriter.flush()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "SubscriptionUpdateWorker: final test-result flush failed", e)
            false
        }
        if (!flushed || !completed) return TestPhaseResult.FAILED

        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateWorker: test phase finished for ${sub.remarks}")
        return TestPhaseResult.SUCCESS
    }

    /** A stuck native probe must not hold the Worker and its batch permit forever. */
    private fun testPhaseTimeoutMs(count: Int): Long =
        (count.toLong() * TEST_TIMEOUT_PER_SERVER_MS + TEST_TIMEOUT_MARGIN_MS)
            .coerceAtMost(TEST_TIMEOUT_MAX_MS)

    private fun retryOrFail(reason: String): Result {
        val attempt = runAttemptCount + 1
        return if (attempt >= MAX_RUN_ATTEMPTS) {
            LogUtil.w(
                AppConfig.TAG,
                "SubscriptionUpdateWorker: giving up after $attempt/$MAX_RUN_ATTEMPTS attempts ($reason)",
            )
            Result.failure()
        } else {
            LogUtil.w(
                AppConfig.TAG,
                "SubscriptionUpdateWorker: retrying attempt $attempt/$MAX_RUN_ATTEMPTS ($reason)",
            )
            Result.retry()
        }
    }

    private fun inputSubIds(): List<String> = buildList {
        inputData.getStringArray(KEY_SUB_IDS)?.forEach(::add)
        inputData.getString(KEY_SUB_ID)?.let(::add)
    }.filter(String::isNotBlank).distinct()

    private data class UpdateOutcome(
        val updated: Boolean,
        val retryable: Boolean = false,
        val terminalFailure: Boolean = false,
    )

    private enum class TestPhaseResult {
        SUCCESS,
        FAILED,
        NOT_STOPPED,
    }

    companion object {
        /** Input key of the subscription guid for a periodic request. */
        internal const val KEY_SUB_ID = "subId"

        /** Input key of a batch of subscription guids for a manual request. */
        internal const val KEY_SUB_IDS = "subIds"

        /** Whether this request bypasses the automatic-update preference. */
        internal const val KEY_FORCED = "forced"

        /** Whether a successful manual request should reset the periodic timer. */
        internal const val KEY_RESCHEDULE_AFTER_SUCCESS = "rescheduleAfterSuccess"

        private const val MAX_RUN_ATTEMPTS = 5
        private const val TEST_TIMEOUT_PER_SERVER_MS = 10_000L
        private const val TEST_TIMEOUT_MARGIN_MS = 120_000L
        private const val TEST_TIMEOUT_MAX_MS = 30 * 60_000L
    }
}

private object SubscriptionUpdateExecutionGate {
    /** Keep the old Service's maximum of two active update batches. */
    val batch = Semaphore(2)

    private class Entry {
        val mutex = Mutex()
        val refs = AtomicInteger(0)
    }

    private val subscriptionLocks = ConcurrentHashMap<String, Entry>()

    suspend fun <T> forSubscription(subId: String, block: suspend () -> T): T {
        val entry = subscriptionLocks.compute(subId) { _, existing ->
            (existing ?: Entry()).also { it.refs.incrementAndGet() }
        }!!
        try {
            return entry.mutex.withLock { block() }
        } finally {
            if (entry.refs.decrementAndGet() == 0) {
                subscriptionLocks.remove(subId, entry)
            }
        }
    }
}
