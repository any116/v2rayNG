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
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestNotification
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class CoreTestService : Service() {

    /**
     * One unit of work, tagged with the request that owns it. Removal from [units] is the atomic
     * claim: the winner is the only party allowed to report a finish or a cancel for the unit.
     */
    private class BatchUnit(val requestId: String, val subscriptionId: String) {
        lateinit var worker: RealPingWorkerService
    }

    private val units: MutableSet<BatchUnit> =
        Collections.newSetFromMap(ConcurrentHashMap<BatchUnit, Boolean>())

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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Service death invalidates every request, so each one gets its own cancel reply. */
    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "CoreTestService destroyed, cancelling ${units.size} units")
        cancelUnits(units.toList())
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            getString(R.string.title_real_ping_all_server),
            cancelAction
        )
        val message = intent?.serializable<TestServiceMessage>("content")
        if (message == null) {
            stopIfIdle(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> handleMeasureStart(message, startId)
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> handleMeasureCancel(message)
            else -> stopIfIdle(startId)
        }
        return START_NOT_STICKY
    }

    private fun handleMeasureStart(message: TestServiceMessage, startId: Int) {
        val requestId = message.requestId
        if (requestId.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "CoreTestService rejected a batch start without a request id")
            stopIfIdle(startId)
            return
        }
        LogUtil.i(AppConfig.TAG, "CoreTestService starting request $requestId for ${message.subscriptionId}")

        // Defence against silently shadowing an older batch: still-registered units are cancelled
        // and reported, never dropped.
        cancelUnits(units.toList())

        val guids = when {
            message.serverGuids.isNotEmpty() -> message.serverGuids
            message.subscriptionId.isNotEmpty() -> MmkvManager.decodeServerList(message.subscriptionId)
            else -> MmkvManager.decodeAllServerList()
        }
        if (guids.isEmpty()) {
            sendCanceled(requestId)
            stopIfIdle(startId)
            return
        }

        val unit = BatchUnit(requestId, message.subscriptionId)
        unit.worker = RealPingWorkerService(
            context = this,
            guids = guids,
            onlyTcp = message.onlyTcp,
            onEvent = { event -> handleWorkerEvent(event, unit) }
        )
        units.add(unit)
        unit.worker.start()
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
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                if (unit !in units) return
                sendNotify(AppConfig.MSG_MEASURE_CONFIG_SUCCESS, unit.requestId, event.guid)
            }

            RealPingEvent.Finish -> {
                // Losing the claim means the unit was already cancelled; do not also finish it.
                if (!units.remove(unit)) return
                applyPostProcessing(unit.subscriptionId)
                sendNotify(AppConfig.MSG_MEASURE_CONFIG_FINISH, unit.requestId)
                stopIfIdle()
            }
        }
    }

    private fun handleMeasureCancel(message: TestServiceMessage) {
        val targets = if (message.requestId.isEmpty()) {
            units.toList()
        } else {
            units.filter { it.requestId == message.requestId }
        }
        LogUtil.i(AppConfig.TAG, "CoreTestService cancelling ${targets.size} units")
        cancelUnits(targets)
        stopIfIdle()
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

    private fun applyPostProcessing(subscriptionId: String) {
        if (subscriptionId.isEmpty()) return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)) {
            AngConfigManager.removeInvalidServer(subscriptionId)
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)) {
            AngConfigManager.sortByTestResultsForSub(subscriptionId)
        }
    }

    private fun sendCanceled(requestId: String) =
        sendNotify(AppConfig.MSG_MEASURE_CONFIG_CANCELED, requestId)

    private fun sendNotify(key: Int, requestId: String, payload: String = "") =
        MessageHelper.sendMsg2UI(this, key, TestNotification(requestId, payload))

    private fun stopIfIdle(startId: Int? = null) {
        if (units.isNotEmpty()) return
        NotificationHelper.stopForeground(this)
        if (startId == null) stopSelf() else stopSelf(startId)
    }
}
