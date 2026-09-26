package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.CoreStartup
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.SoftReference
import javax.inject.Inject

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it does not use
 * Android VpnService — traffic is routed by iptables instead (see [RootProxyManager]).
 */
@AndroidEntryPoint
class CoreRootService : Service(), ServiceControl {

    /**
     * Injected rather than hard-coded so the root shell work is driven by the same binding the data
     * layer uses. Field injection completes inside `super.onCreate()`.
     */
    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    /**
     * `by lazy` on purpose: a property initialiser is evaluated during construction, before Hilt has
     * injected [io]. First touch is in [onStartCommand], which always runs after `onCreate()`.
     */
    private val serviceScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + io) }

    private var setupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before any database access, same reason as the VPN service.
        NotificationManager.ensureForeground()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")

        if (CoreServiceManager.isRunning()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Root: Core is already running")
            return START_STICKY
        }

        val requestedGuid = intent?.getStringExtra(LauncherManager.EXTRA_SELECTED_GUID)

        // Child of the service-owned scope, so onDestroy cancels it. A second equivalent command
        // must not install a second rule set: cancel the in-flight attempt before replacing it.
        setupJob?.cancel()
        setupJob = serviceScope.launch {
            if (!CoreStartup.refreshPreferences(this@CoreRootService)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: storage not ready; aborting start")
                stopService()
                return@launch
            }
            if (!requestedGuid.isNullOrBlank()) {
                CoreServiceManager.adoptSelectedGuid(this@CoreRootService, requestedGuid)
            }

            // In-process core first (this also posts the foreground notification), then install the
            // root routing. Order is load-bearing: rules must never point at a dead core.
            if (!CoreServiceManager.startCoreLoop(null)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: core failed to start")
                stopService()
                return@launch
            }
            if (!RootProxyManager.start(this@CoreRootService)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode, stopping")
                stopService()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Wait for any in-flight async setup before tearing down. The rules are installed off the
        // main thread and can take seconds (the setup script waits for the tun to appear); a stop
        // arriving in that window would tear down first, and the setup would then re-install rules
        // and a tun pointing at a dead core, blackholing traffic until the next start/stop cycle.
        runBlocking { setupJob?.cancelAndJoin() }
        setupJob = null
        serviceScope.cancel()
        // Remove routing rules BEFORE stopping the core so traffic is never redirected to a dead
        // listener. Synchronous on purpose — leaving rules behind breaks the network.
        RootProxyManager.stop(this)
        CoreServiceManager.stopCoreLoop()
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        stopSelf()
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        // Must not touch any injected field here — injection has not happened yet.
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }
}
