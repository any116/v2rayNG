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
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference
import javax.inject.Inject

@AndroidEntryPoint
class CoreProxyOnlyService : Service(), ServiceControl {

    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + io) }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before any database access, same reason as the VPN service.
        NotificationManager.ensureForeground()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service command received")

        if (CoreServiceManager.isRunning()) {
            LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Core is already running")
            return START_STICKY
        }

        val requestedGuid = intent?.getStringExtra(LauncherManager.EXTRA_SELECTED_GUID)
        serviceScope.launch {
            CoreStartup.refreshPreferences(this@CoreProxyOnlyService)
            if (!requestedGuid.isNullOrBlank()) {
                CoreServiceManager.adoptSelectedGuid(this@CoreProxyOnlyService, requestedGuid)
            }
            if (!CoreServiceManager.startCoreLoop(null)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to start core loop")
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        CoreServiceManager.stopCoreLoop()
        serviceScope.cancel()
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
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }
}
