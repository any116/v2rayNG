package com.v2ray.ang.handler

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkRequest
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.dao.SubscriptionDao
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object SubscriptionUpdater {

    private val subscriptionDao: SubscriptionDao
        get() = PlatformDependencies.subscriptionDao(AngApplication.application)

    // -------------------------------------------------------------------------
    // Public API — the only methods external callers should ever use
    // -------------------------------------------------------------------------

    /**
     * Sync all subscription tasks with current settings.
     *
     * Startup/boot callers should use the default mode so existing periodic work is kept.
     * Use forceReschedule=true only when the next run time needs to be recalculated from
     * the latest persisted subscription state (for example after a manual refresh).
     * Call from: MainActivity.onCreate(), or [SubscriptionScheduleWorker] after boot.
     */
    suspend fun sync(
        context: Context = AngApplication.application,
        forceReschedule: Boolean = false
    ): Boolean {
        val migrating = needsWorkerMigration()
        val existingWorkPolicy = when {
            forceReschedule -> ExistingPeriodicWorkPolicy.REPLACE

            // The worker class became top-level when it was converted to @HiltWorker, and
            // WorkManager persists the class name. Rows written by an older build still name the
            // removed nested class and would fail to instantiate forever under KEEP. UPDATE
            // rewrites the work spec, including the class name, while preserving the existing
            // period, which REPLACE would reset.
            migrating -> ExistingPeriodicWorkPolicy.UPDATE

            else -> ExistingPeriodicWorkPolicy.KEEP
        }

        // Include disabled/invalid rows so scheduleOne() can cancel a work spec that was created
        // before the row was disabled or its URL was cleared.
        var allOperationsSucceeded = true
        for (sub in subscriptionDao.all()) {
            if (!scheduleOne(context, sub, existingWorkPolicy)) {
                allOperationsSucceeded = false
            }
        }

        if (migrating && allOperationsSucceeded) {
            markWorkerMigrated()
        }
        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: sync complete forceReschedule=$forceReschedule " +
                    "migrating=$migrating operationsSucceeded=$allOperationsSucceeded"
        )
        if (!allOperationsSucceeded) enqueueSync(context)
        return allOperationsSucceeded
    }

    /** Hands scheduling to WorkManager so a BroadcastReceiver can finish promptly. */
    suspend fun enqueueSync(
        context: Context = AngApplication.application,
        requireAutoStart: Boolean = false,
    ): Boolean {
        val policy = if (requireAutoStart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        repeat(2) { attempt ->
            try {
                RemoteWorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        SubscriptionScheduleWorker.UNIQUE_WORK_NAME,
                        policy,
                        OneTimeWorkRequestBuilder<SubscriptionScheduleWorker>()
                            .setInputData(
                                workDataOf(
                                    SubscriptionScheduleWorker.KEY_REQUIRE_AUTO_START to requireAutoStart,
                                )
                            )
                            .setBackoffCriteria(
                                BackoffPolicy.EXPONENTIAL,
                                BACKOFF_DELAY_SECONDS,
                                TimeUnit.SECONDS,
                            )
                            .build(),
                    )
                    .awaitRemote()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (attempt == 1) {
                    LogUtil.e(AppConfig.TAG, "SubscriptionUpdater: failed to enqueue schedule sync", e)
                } else {
                    delay(250L)
                }
            }
        }
        return false
    }

    /**
     * Sync a single subscription's task.
     * Call from: SubEditActivity after saving, after a manual update (to reset the timer).
     */
    suspend fun syncOne(context: Context = AngApplication.application, subId: String): Boolean {
        val subItem = subscriptionDao.find(subId) ?: return false
        val scheduled = scheduleOne(context, subItem, ExistingPeriodicWorkPolicy.REPLACE)
        if (!scheduled) enqueueSync(context)
        return scheduled
    }

    /**
     * Cancel the auto-update task for a single subscription.
     * Call from: when a subscription is deleted.
     *
     * Awaits WorkManager cancellation before a caller removes the corresponding database row.
     */
    suspend fun cancelOne(context: Context = AngApplication.application, subId: String) {
        val rw = RemoteWorkManager.getInstance(context)
        cancelPeriodic(context, subId)
        // Clean up manual requests created by the pre-batch implementation. New manual requests
        // are tagged batches and intentionally are not cancelled when one subscription is deleted.
        rw.cancelUniqueWork(legacyManualTaskName(subId))
            .awaitQuietly("cancel ${legacyManualTaskName(subId)}")
    }

    /**
     * Enqueues an immediate background refresh for the selected subscriptions.
     *
     * Manual requests are batched so a multi-selection does not create one foreground Worker and
     * notification per subscription. The input is conservatively split because WorkManager Data
     * has a 10 KB serialized-size limit.
     */
    suspend fun enqueueOneTime(
        context: Context = AngApplication.application,
        subIds: List<String>,
        forced: Boolean,
    ): Boolean {
        val targets = subIds.filter(String::isNotBlank).distinct()
        if (targets.isEmpty()) return false

        return manualEnqueueMutex.withLock {
            val rw = RemoteWorkManager.getInstance(context)
            try {
                // Manual requests use ordinary WorkRequests rather than a fixed unique name. A
                // second refresh must never be silently discarded while an earlier one runs.
                val requests = ArrayList<WorkRequest>()
                targets.chunked(MAX_MANUAL_BATCH_SIZE).forEach { batch ->
                    val request = OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>()
                        .setConstraints(connectedNetworkConstraints())
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .setInputData(
                            workDataOf(
                                SubscriptionUpdateWorker.KEY_SUB_IDS to batch.toTypedArray(),
                                SubscriptionUpdateWorker.KEY_FORCED to forced,
                                SubscriptionUpdateWorker.KEY_RESCHEDULE_AFTER_SUCCESS to true,
                            )
                        )
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            BACKOFF_DELAY_SECONDS,
                            TimeUnit.SECONDS,
                        )
                        .addTag(MANUAL_WORK_TAG)
                        .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
                        .build()
                    requests += request
                }
                rw.enqueue(requests).awaitRemote()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "SubscriptionUpdater: failed to enqueue manual update", e)
                false
            }
        }
    }

    /**
     * Update the last updated timestamp and reschedule the task.
     * This is used to reset the periodic timer and prevent rapid rescheduling loops.
     */
    suspend fun updateLastUpdatedAndReschedule(
        context: Context = AngApplication.application,
        subId: String
    ): Boolean {
        val subItem = subscriptionDao.find(subId) ?: return false
        val now = System.currentTimeMillis()
        if (subscriptionDao.touch(subId, now) == 0) return false
        // Reschedule from the value just written rather than re-reading the row.
        val scheduled = scheduleOne(context, subItem.copy(lastUpdated = now), ExistingPeriodicWorkPolicy.REPLACE)
        if (!scheduled) enqueueSync(context)
        return scheduled
    }

    /**
     * Rebuilds the periodic request without touching lastUpdated.
     *
     * The Worker calls this after [AngConfigManager.updateConfigViaSub] has already persisted the
     * successful update timestamp. Keeping the two operations separate avoids a redundant Room
     * write and preserves the timestamp written by the importer.
     */
    suspend fun reschedule(
        context: Context = AngApplication.application,
        subId: String,
    ): Boolean {
        val subItem = subscriptionDao.find(subId) ?: return false
        return scheduleOne(context, subItem, ExistingPeriodicWorkPolicy.REPLACE)
    }

    // -------------------------------------------------------------------------
    // Worker class migration
    // -------------------------------------------------------------------------

    /**
     * Generation of the persisted worker class names.
     *
     * 1 — `SubscriptionUpdater$UpdateTask`, the nested CoroutineWorker.
     * 2 — `SubscriptionUpdateWorker`, top-level and @HiltWorker.
     *
     * Bump this whenever a worker is renamed or moved, otherwise already-enqueued rows keep
     * naming a class that no longer exists.
     */
    private const val WORKER_SCHEMA_VERSION = 2

    private fun needsWorkerMigration(): Boolean =
        Prefs.string(AppConfig.CACHE_WORKER_SCHEMA_VERSION)?.toIntOrNull() != WORKER_SCHEMA_VERSION

    private suspend fun markWorkerMigrated() =
        Prefs.putString(AppConfig.CACHE_WORKER_SCHEMA_VERSION, WORKER_SCHEMA_VERSION.toString())

    // -------------------------------------------------------------------------
    // Internal scheduling logic
    // -------------------------------------------------------------------------

    private fun taskName(subId: String) = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_$subId"

    private fun connectedNetworkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Takes the already loaded item so callers read the table once; the only suspension is waiting
     * for the remote WorkManager operation to be accepted.
     */
    private suspend fun scheduleOne(
        context: Context,
        subItem: SubscriptionItem,
        existingWorkPolicy: ExistingPeriodicWorkPolicy
    ): Boolean {
        val subId = subItem.guid
        if (!subItem.enabled || !subItem.autoUpdate || subItem.url.isEmpty()) {
            // sync() visits every row so stale periodic specs are removed, but legacy manual
            // specs do not need a second Binder round trip: the Worker skips missing/disabled rows.
            val cancelled = cancelPeriodic(context, subId)
            LogUtil.d(AppConfig.TAG, "SubscriptionUpdater: cancelled task for ${subItem.remarks}")
            return cancelled
        }
        val rw = RemoteWorkManager.getInstance(context)

        val intervalMinutes = maxOf(
            AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES,
            subItem.updateInterval
        )

        // Base initial delay on the last successful update time persisted in subscription.
        val lastUpdated = subItem.lastUpdated
        val intervalMillis = intervalMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        var initialDelayMillis = if (lastUpdated <= 0L) {
            0L
        } else {
            maxOf(0L, lastUpdated + intervalMillis - now)
        }

        // Add a small floor to initial delay to prevent rapid rescheduling loops.
        if (existingWorkPolicy == ExistingPeriodicWorkPolicy.REPLACE && initialDelayMillis < 5000L) {
            initialDelayMillis = 5000L
        }

        val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
            intervalMinutes,
            TimeUnit.MINUTES
        )
            .setConstraints(connectedNetworkConstraints())
            .setInputData(
                workDataOf(
                    SubscriptionUpdateWorker.KEY_SUB_ID to subId,
                    SubscriptionUpdateWorker.KEY_FORCED to true,
                )
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_DELAY_SECONDS,
                TimeUnit.SECONDS,
            )
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            .build()

        return try {
            rw.enqueueUniquePeriodicWork(
                taskName(subId),
                existingWorkPolicy,
                request
            ).awaitRemote()

            LogUtil.i(
                AppConfig.TAG,
                "SubscriptionUpdater: scheduled [${subItem.remarks}] interval=${intervalMinutes}min " +
                        "initialDelay=${initialDelayMillis / 1000}s policy=$existingWorkPolicy"
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LogUtil.e(AppConfig.TAG, "SubscriptionUpdater: schedule ${subItem.remarks} failed", e)
            false
        }
    }

    private suspend fun cancelPeriodic(context: Context, subId: String): Boolean {
        return try {
            RemoteWorkManager.getInstance(context)
                .cancelUniqueWork(taskName(subId))
                .awaitRemote()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LogUtil.e(AppConfig.TAG, "SubscriptionUpdater: cancel ${taskName(subId)} failed", e)
            false
        }
    }

    private const val BACKOFF_DELAY_SECONDS = 30L
    private const val MAX_MANUAL_BATCH_SIZE = 100
    private const val MANUAL_BATCH_TASK_NAME = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_manual"
    private const val MANUAL_WORK_TAG = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_manual_work"
    private val manualEnqueueMutex = Mutex()

    private fun legacyManualTaskName(subId: String) = "${MANUAL_BATCH_TASK_NAME}_$subId"
}

private suspend fun <T> ListenableFuture<T>.awaitRemote(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try {
            continuation.resume(get())
        } catch (e: java.util.concurrent.ExecutionException) {
            continuation.resumeWithException(e.cause ?: e)
        } catch (e: CancellationException) {
            continuation.cancel(e)
        } catch (e: Throwable) {
            continuation.resumeWithException(e)
        }
    }, Executor { command -> command.run() })
    continuation.invokeOnCancellation { cancel(true) }
}

/** Scheduling is best effort and must never block a database mutation such as subscription delete. */
private suspend fun ListenableFuture<*>.awaitQuietly(operation: String) {
    try {
        awaitRemote()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LogUtil.e(AppConfig.TAG, "SubscriptionUpdater: $operation failed", e)
    }
}
