package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.CoreStartup
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {

    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)

    /** Owns the whole startup sequence; injected io is available from onCreate onwards. */
    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + io) }
    private var startJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")
        if (isRunning) {
            try {
                if (::mInterface.isInitialized) mInterface.close()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
        }
        serviceScope.cancel()
        unlockStart()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must precede any database access: the 10s startForeground window is already running when
        // the daemon process is the one that opens the database first (boot autostart).
        NotificationManager.ensureForeground()

        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        if (isSystemVpnStart) {
            unlockStart()
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return START_NOT_STICKY
        }

        val requestedGuid = intent?.getStringExtra(LauncherManager.EXTRA_SELECTED_GUID)
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")

        startJob?.cancel()
        startJob = serviceScope.launch {
            if (!startSequence(requestedGuid)) {
                unlockStart()
                stopSelf()
            }
        }
        return START_STICKY
    }

    /**
     * Order matters and is load-bearing:
     *  1. warm the preference snapshot for this process,
     *  2. adopt a requested guid,
     *  3. resolve the LAN-bypass policy (needs the database — this is why it cannot run at tun
     *     build time any more),
     *  4. establish the tun interface,
     *  5. start the core.
     */
    private suspend fun startSequence(requestedGuid: String?): Boolean {
        CoreStartup.refreshPreferences(this)

        if (!requestedGuid.isNullOrBlank()) {
            CoreServiceManager.adoptSelectedGuid(this, requestedGuid)
        }

        val bypassLan = SettingsManager.routingRulesetsBypassLan()
        if (!setupVpnService(bypassLan)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Setup failed")
            return false
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return false
        }
        RootLanSharing.startClientSharing(this)
        return true
    }

    override fun getService(): Service = this

    override fun startService() {
        // Kept for ServiceControl; the real sequence is triggered by onStartCommand.
        startJob = serviceScope.launch { CoreServiceManager.startCoreLoop(mInterface) }
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean = protect(socket)

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean =
        super<VpnService>.setUnderlyingNetworks(networks)

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    private fun setupVpnService(bypassLan: Boolean): Boolean {
        if (prepare(this) != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }
        if (configureVpnService(bypassLan) != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }
        runTun2socks()
        return true
    }

    private fun configureVpnService(bypassLan: Boolean): Boolean {
        val builder = Builder()
        configureNetworkSettings(builder, bypassLan)
        configurePerAppProxy(builder)

        try {
            if (::mInterface.isInitialized) mInterface.close()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        configurePlatformFeatures(builder)

        return try {
            mInterface = builder.establish()!!
            isRunning = true
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
            false
        }
    }

    private fun configureNetworkSettings(builder: Builder, bypassLan: Boolean) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()

        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 32)

        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (Prefs.bool(AppConfig.PREF_IPV6_ENABLED)) {
            builder.addAddress(vpnConfig.ipv6Client, 128)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) builder.addDnsServer(it)
        }
    }

    private fun configurePlatformFeatures(builder: Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (Prefs.bool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * - per-app proxy off, or no apps selected: disallow our own package.
     * - bypass mode: disallow every selected app (plus self).
     * - proxy mode: allow only the selected apps (never self).
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        if (!Prefs.bool(AppConfig.PREF_PER_APP_PROXY)) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val apps = Prefs.stringSet(AppConfig.PREF_PER_APP_PROXY_SET).toMutableSet()
        if (apps.isEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = Prefs.bool(AppConfig.PREF_BYPASS_APPS)
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    builder.addDisallowedApplication(it)
                } else {
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    private fun runTun2socks() {
        tun2SocksService = if (SettingsManager.isUsingHevTun()) {
            TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            null
        }
        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
        unlockStart()
        isRunning = false

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        RootLanSharing.stopClientSharing(this)
        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            // stopSelf must precede mInterface.close(): otherwise a later core start reports the
            // SOCKS port as in use, because the first core failed to release it.
            stopSelf()

            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }

    fun tryLockStart(): Boolean = isStartingLock.compareAndSet(false, true)

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}
