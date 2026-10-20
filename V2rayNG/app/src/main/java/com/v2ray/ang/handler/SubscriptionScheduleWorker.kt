package com.v2ray.ang.handler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.util.LogUtil
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Performs subscription scheduling outside the short-lived boot broadcast callback. */
@HiltWorker
class SubscriptionScheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        StorageBootstrap.retry()
        if (!StorageBootstrap.awaitReadyOrNull()) {
            LogUtil.w(AppConfig.TAG, "SubscriptionScheduleWorker: storage not ready")
            return Result.retry()
        }
        if (inputData.getBoolean(KEY_REQUIRE_AUTO_START, false)
            && !Prefs.bool(AppConfig.PREF_IS_BOOTED, false)
        ) {
            LogUtil.i(AppConfig.TAG, "SubscriptionScheduleWorker: auto-start scheduling disabled")
            return Result.success()
        }

        return try {
            if (withContext(io) { SubscriptionUpdater.sync(applicationContext) }) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "SubscriptionScheduleWorker failed", e)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "subscription_updater_schedule"
        const val KEY_REQUIRE_AUTO_START = "requireAutoStart"
    }
}
