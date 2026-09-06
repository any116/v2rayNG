package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Periodic trigger for one subscription's auto-update.
 *
 * Top-level rather than nested in [SubscriptionUpdater] because @HiltWorker only supports
 * top-level classes. The rename is not free: WorkManager persists the worker class name, so rows
 * enqueued by an older build still point at `SubscriptionUpdater$UpdateTask`. See
 * [SubscriptionUpdater.WORKER_SCHEMA_VERSION] for the one-time rewrite that repoints them.
 *
 * This runs in `:bg`, which has its own Application instance and therefore its own Hilt graph.
 * Only process-agnostic dependencies may be injected here: per repository-rules.md section 5, a
 * Worker talks to `handler/` directly and must never pull in a UI-process Repository. The
 * dispatcher is the one real injected dependency, so the MMKV and Binder work below stops relying
 * on whichever thread the WorkManager executor happens to offer.
 */
@HiltWorker
class SubscriptionUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result = withContext(io) {
        val subId = inputData.getString(KEY_SUB_ID)
        LogUtil.i(AppConfig.TAG, "SubscriptionUpdater update starting via Service: $subId")

        if (subId.isNullOrEmpty()) {
            LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: missing subId in worker input")
            return@withContext Result.success()
        }

        SubscriptionUpdater.updateLastUpdatedAndReschedule(applicationContext, subId)

        MessageHelper.sendMsg2SubscriptionService(
            applicationContext,
            SubscriptionUpdateMessage(AppConfig.MSG_SUB_UPDATE_START, true, listOf(subId))
        )

        Result.success()
    }

    companion object {
        /** Input key of the subscription guid. Read by the scheduler in [SubscriptionUpdater]. */
        internal const val KEY_SUB_ID = "subId"
    }
}
