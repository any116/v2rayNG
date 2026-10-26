package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.dto.ConnectionTestResponse
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object CoreServiceManager {

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null

    private val coreLifecycleMutex = Mutex()
    private val lifecycleGeneration = AtomicLong(0)
    private val reloadSequence = AtomicLong(0)
    private val lifecycleJobsLock = Any()
    private var reloadJob: Job? = null
    private var stopJob: Job? = null
    private val receiverRegistered = AtomicBoolean(false)

    /** Tun descriptor the core was started with; null in proxy-only and root modes. */
    @Volatile
    private var currentVpnInterface: ParcelFileDescriptor? = null

    private val bgScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Uncaught background failure", error)
        }
    )

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    fun isRunning() = coreController.isRunning

    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    fun getCurrentConfig(): ProfileItem? = currentConfig

    /** Captures the lifecycle generation when a start request is accepted. */
    fun startRequestToken(): Long = lifecycleGeneration.get()

    /**
     * Persists a guid requested by a receiver or Tasker.
     */
    suspend fun adoptSelectedGuid(context: Context, guid: String) {
        runCatching { PlatformDependencies.profileDao(context).writeSelectedGuid(guid) }
            .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-Manager: failed to adopt guid $guid", it) }
    }

    /**
     * Starts the core. Suspends for the whole startup sequence, so callers must run it in a scope:
     * the foreground notification is already posted by then, and the database work must not sit
     * between NotificationManager.ensureForeground() and the tun setup.
     *
     * @return false when the core is already running or no service is attached.
     */
    suspend fun startCoreLoop(
        vpnInterface: ParcelFileDescriptor?,
        requestToken: Long = startRequestToken(),
    ): Boolean {
        val pendingStop = synchronized(lifecycleJobsLock) { stopJob }
        pendingStop?.join()
        if (requestToken != lifecycleGeneration.get()) return false
        return coreLifecycleMutex.withLock {
            if (requestToken != lifecycleGeneration.get()) return@withLock false
            startCoreLoopLocked(vpnInterface, requestToken)
        }
    }

    private suspend fun startCoreLoopLocked(vpnInterface: ParcelFileDescriptor?, requestToken: Long): Boolean {
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }
        val service = getService() ?: run {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }
        return try {
            doStartCoreLoop(service, vpnInterface, requestToken)
            true
        } catch (e: CancellationException) {
            unregisterReceiverSafely(service)
            throw e
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            unregisterReceiverSafely(service)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            false
        }
    }

    @Throws(Exception::class)
    private suspend fun doStartCoreLoop(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        requestToken: Long,
    ) {
        ensureStartCurrent(requestToken)
        registerReceiverSafely(service)
        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface, requestToken = requestToken)
        startNetworkMonitor(service)
    }

    private fun ensureStartCurrent(requestToken: Long) {
        check(requestToken == lifecycleGeneration.get()) { "core start superseded by stop" }
    }

    private fun registerReceiverSafely(service: Service) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_USER_PRESENT)
        try {
            ContextCompat.registerReceiver(service, mMsgReceive, filter, Utils.receiverFlags())
        } catch (e: Exception) {
            receiverRegistered.set(false)
            throw e
        }
    }

    private fun unregisterReceiverSafely(service: Service) {
        if (!receiverRegistered.compareAndSet(true, false)) return
        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }
    }

    @Throws(Exception::class)
    private suspend fun launchCore(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean = false,
        requestToken: Long? = null,
    ) {
        // Authoritative single-row reads: this is a strong-consistency point, not a snapshot read.
        // The caller refreshed the SettingsStore snapshot first, so every Prefs.* read below is warm.
        val profileDao = PlatformDependencies.profileDao(service)
        val settings = PlatformDependencies.settingsStore(service)

        val guid = profileDao.selectedGuid()
            ?: settings.string(SettingsStore.KEY_SELECTED_SERVER)
            ?: error("No server selected")
        val config = profileDao.findByGuid(guid) ?: error("Failed to decode server config")

        if (config.insecure == true && config.pinnedCA256.isNullOrEmpty()) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_WARN_INSECURE, "")
        }

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }
        requestToken?.let(::ensureStartCurrent)

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) "127.0.0.1:${Utils.findRandomFreePort()}" else ""
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        requestToken?.let(::ensureStartCurrent)
        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(result.content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> Unit
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false
        val hadResources = coreController.isRunning ||
            receiverRegistered.get() ||
            networkMonitor != null ||
            browserDialer != null ||
            synchronized(lifecycleJobsLock) { stopJob?.isActive == true }
        if (!hadResources) return false

        lifecycleGeneration.incrementAndGet()
        reloadSequence.incrementAndGet()
        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null

        CoreNativeManager.reconcileBrowserDialer("")
        browserDialer?.let {
            runCatching { it.stop() }
                .onFailure { error -> LogUtil.w(AppConfig.TAG, "StartCore-Manager: Failed to stop dialer", error) }
        }
        browserDialer = null
        unregisterReceiverSafely(service)
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()

        synchronized(lifecycleJobsLock) {
            reloadJob?.cancel()
            reloadJob = null
            if (stopJob?.isActive != true) {
                // Native teardown is blocking; keep it off whichever thread the caller is on.
                stopJob = bgScope.launch {
                    coreLifecycleMutex.withLock {
                        try {
                            if (coreController.isRunning) coreController.stopLoop()
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                        } finally {
                            currentConfig = null
                        }
                    }
                }
            }
        }

        return true
    }

    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onNetworkLost = { resetConnections() },
            onHandover = { scheduleCoreReload() },
        ).also { it.register() }
    }

    private fun resetConnections() {
        val generation = lifecycleGeneration.get()
        bgScope.launch {
            if (generation != lifecycleGeneration.get() || !isRunning()) return@launch
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Upstream network lost; resetting connections")
            runCatching { coreController.resetConnections() }
                .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reset connections", it) }
        }
    }

    private fun scheduleCoreReload() {
        val generation = lifecycleGeneration.get()
        val sequence = reloadSequence.incrementAndGet()
        synchronized(lifecycleJobsLock) {
            reloadJob?.cancel()
            reloadJob = bgScope.launch { reloadCore(generation, sequence) }
        }
    }

    /**
     * Restarts the core in place after the upstream network changed: service, notification and VPN
     * interface all stay up, so none of this is visible.
     *
     * The config is rebuilt on purpose — outbound server domains are resolved while building it,
     * and an address resolved on a network that is gone can be unusable on the new one.
     */
    private suspend fun reloadCore(generation: Long, sequence: Long): Boolean {
        if (generation != lifecycleGeneration.get()) return false
        val service = getService()
        if (service == null) return false

        return try {
            coreLifecycleMutex.withLock {
                if (generation != lifecycleGeneration.get() || sequence != reloadSequence.get() || !isRunning()) {
                    return@withLock false
                }

                val tunFd = currentVpnInterface
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")
                withContext(NonCancellable) {
                    coreController.stopLoop()
                    launchCore(service, tunFd, isReload = true)
                }
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops; querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        val result = ArrayList<OutboundTrafficStat>()
        coreController.queryAllOutboundTrafficStats().split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach
            val value = parts[2].toLongOrNull() ?: return@forEach
            result.add(OutboundTrafficStat(tag = parts[0], direction = parts[1], value = value))
        }
        return result
    }

    /**
     * Measures the delay of the running configuration for one request.
     * @return true only when the measurement was accepted and started, so the caller can
     * acknowledge an ordered broadcast honestly.
     */
    private fun measureV2rayDelay(requestId: String): Boolean {
        if (requestId.isEmpty()) return false
        if (!isRunning()) return false
        val service = getService() ?: return false
        val generation = lifecycleGeneration.get()

        bgScope.launch {
            var replied = false
            try {
                var time = -1L
                var errorStr = ""
                try {
                    if (generation != lifecycleGeneration.get() || !isRunning()) {
                        errorStr = "core not running"
                    } else {
                        time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
                MessageHelper.sendMsg2UI(
                    service,
                    AppConfig.MSG_MEASURE_DELAY_RESULT,
                    ConnectionTestResponse(requestId, ConnectionTestResult(time, errorStr))
                )
                replied = true
            } catch (e: Throwable) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Delay request $requestId failed", e)
            } finally {
                // A request must always be closed, otherwise the UI stays in the testing state.
                if (!replied) {
                    runCatching {
                        MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCELED, requestId)
                    }
                }
            }
        }
        return true
    }

    private fun getService(): Service? = serviceControl?.get()?.getService()

    private class CoreCallback : CoreCallbackHandler {
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(
            network: String,
            srcIP: String,
            srcPort: Long,
            destIP: String,
            destPort: Long
        ): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            // An unresolved destination cannot be attributed; do not guess.
            if (destIP.isBlank() || destPort == 0L) return -1L

            return try {
                cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
            } catch (_: Exception) {
                -1L
            }
        }
    }

    private class ReceiveMessageHandler : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    val running = isRunning()
                    // Ordered senders (widget) need a definitive answer: no daemon, no RESULT_OK.
                    if (isOrderedBroadcast && running) resultCode = Activity.RESULT_OK
                    MessageHelper.sendMsg2UI(
                        serviceControl.getService(),
                        if (running) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING,
                        ""
                    )
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> Unit

                AppConfig.MSG_LOCALE_CHANGED -> {
                    val localeCode = intent?.getStringExtra("content")
                    NotificationManager.refreshNotification(localeCode)
                }

                AppConfig.MSG_STATE_START -> Unit

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    bgScope.launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    val requestId = intent.getStringExtra("content").orEmpty()
                    val accepted = measureV2rayDelay(requestId)
                    if (isOrderedBroadcast && accepted) resultCode = Activity.RESULT_OK
                    if (!accepted && requestId.isNotEmpty()) {
                        MessageHelper.sendMsg2UI(
                            serviceControl.getService(),
                            AppConfig.MSG_MEASURE_DELAY_CANCELED,
                            requestId
                        )
                    }
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
