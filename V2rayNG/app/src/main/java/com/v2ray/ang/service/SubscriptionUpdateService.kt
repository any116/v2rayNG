package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@AndroidEntryPoint
class SubscriptionUpdateService : Service() {

    override fun attachBaseContext(newBase: Context?) {
        // No injected field may be read here: injection happens inside super.onCreate().
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    /** The one real injected dependency; replaces the previously hard-coded Dispatchers.IO. */
    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    private val serviceJob = Job()

    /**
     * `by lazy` because [io] is only available after `super.onCreate()`. A property initialiser
     * would run during construction and crash on the uninitialised lateinit.
     */
    private val serviceScope: CoroutineScope by lazy { CoroutineScope(io + serviceJob) }

    private val runningTasks = AtomicInteger(0)

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())

    private val updateSemaphore = Semaphore(2)

    private val writerScope: CoroutineScope by lazy { CoroutineScope(io + SupervisorJob()) }

    private val resultWriter: TestResultWriter by lazy {
        TestResultWriter(
            dao = PlatformDependencies.profileDao(this),
            scope = writerScope,
        ).also { it.start() }
    }

    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService is being destroyed")
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        CoroutineScope(io).launch {
            try {
                resultWriter.stop()
            } finally {
                writerScope.cancel()
            }
        }
        serviceJob.cancel()
        NotificationHelper.stopForeground(this)
        NotificationHelper.cancel(NotificationChannelType.SUBSCRIPTION_UPDATE, this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.SUBSCRIPTION_UPDATE,
            getString(R.string.title_pref_auto_update_subscription),
            getString(R.string.app_name)
        )
        val message = intent?.serializable<SubscriptionUpdateMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_SUB_UPDATE_START -> handleUpdateStart(message)
            AppConfig.MSG_SUB_UPDATE_CANCEL -> {
                NotificationHelper.stopForeground(this)
                stopSelf(startId)
            }

            else -> {
                NotificationHelper.stopForeground(this)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleUpdateStart(message: SubscriptionUpdateMessage) {
        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService starting update task for ${message.subIds.size} subscriptions")

        runningTasks.incrementAndGet()
        serviceScope.launch {
            updateSemaphore.withPermit {
                try {
                    message.subIds.forEach { subId ->
                        updateSingle(subId, message.forcedUpdate)
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "SubscriptionUpdateService update failed", e)
                } finally {
                    if (runningTasks.decrementAndGet() == 0 && activeWorkers.isEmpty()) {
                        NotificationHelper.stopForeground(this@SubscriptionUpdateService)
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun updateSingle(subId: String, forcedUpdate: Boolean) {
        val subItem = PlatformDependencies.subscriptionDao(this).find(subId) ?: return
        if (!subItem.enabled || subItem.url.isEmpty()) {
            return
        }

        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: Updating ${subItem.remarks}")
        showNotification(
            context = this,
            titleResId = R.string.title_pref_auto_update_subscription,
            content = getString(R.string.subscription_update_updating, subItem.remarks)
        )

        if (forcedUpdate || Prefs.bool(AppConfig.PREF_UPDATE_SUBSCRIPTION, false)) {
            AngConfigManager.updateConfigViaSub(subItem)
        }

        if (Prefs.bool(AppConfig.PREF_AUTO_TEST_AFTER_UPDATE_SUBSCRIPTION, false)) {
            testSubscriptionServers(subItem)

            if (Prefs.bool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: removing invalid servers for ${subItem.remarks}")
                showNotification(
                    context = this,
                    titleResId = R.string.title_del_invalid_config,
                    content = subItem.remarks
                )
                AngConfigManager.removeInvalidServer(subId)
            }
            if (Prefs.bool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: sorting servers for ${subItem.remarks}")
                showNotification(
                    context = this,
                    titleResId = R.string.title_sort_by_test_results,
                    content = subItem.remarks
                )
                AngConfigManager.sortByTestResultsForSub(subId)
            }
        }

        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: Finished ${subItem.remarks}")
    }

    private suspend fun testSubscriptionServers(sub: SubscriptionItem) {
        val subId = sub.guid
        LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: starting test phase for ${sub.remarks}")
        showNotification(
            context = this,
            titleResId = R.string.title_real_ping_all_server,
            content = sub.remarks
        )

        val profileDao = PlatformDependencies.profileDao(this)
        val guids = profileDao.guidsInGroup(subId)
        if (guids.isNotEmpty()) {
            // The :tasks process snapshot may still be cold or stale. Refresh before reading the
            // concurrency preference, matching CoreTestService. A failure degrades to defaults.
            runCatching { PlatformDependencies.settingsStore(this@SubscriptionUpdateService).refresh() }
                .onFailure { LogUtil.e(AppConfig.TAG, "SubscriptionUpdateService: preference refresh failed", it) }
            val concurrency = SettingsManager.getRealPingConcurrency()

            val deferred = CompletableDeferred<Unit>()
            lateinit var worker: RealPingWorkerService
            worker = RealPingWorkerService(
                context = this,
                profileDao = profileDao,
                guids = guids,
                concurrency = concurrency,
                onEvent = { event ->
                    handleWorkerEvent(event, sub.remarks) {
                        activeWorkers.remove(worker)
                        deferred.complete(Unit)
                    }
                }
            )
            activeWorkers.add(worker)
            worker.start()
            deferred.await()
            resultWriter.flush()
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: test phase finished for ${sub.remarks}")
        }
    }

    private fun handleWorkerEvent(event: RealPingEvent, remarks: String, onWorkerDone: () -> Unit) {
        when (event) {
            is RealPingEvent.Progress -> {
                val notificationText = getString(
                    R.string.subscription_update_progress,
                    event.text,
                    remarks
                )
                showNotification(
                    context = this,
                    titleResId = R.string.title_real_ping_all_server,
                    content = notificationText
                )
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdateService: ${event.text} in $remarks")
            }

            is RealPingEvent.Result -> {
                resultWriter.record(event.guid, event.delayMillis)
            }

            is RealPingEvent.Finish -> {
                onWorkerDone()
            }
        }
    }

    private fun showNotification(context: Context, titleResId: Int, content: String) {
        NotificationHelper.notify(
            NotificationChannelType.SUBSCRIPTION_UPDATE,
            context,
            context.getString(titleResId),
            content
        )
    }
}
