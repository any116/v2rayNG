package com.v2ray.ang

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.data.DatabaseIntegrity
import com.v2ray.ang.data.ImportBuffer
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.di.ApplicationScope
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.components.ThemeManager
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltAndroidApp
class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    /** The invalidation observer must be started once per process, not once per attempt. */
    private val observerStarted = AtomicBoolean(false)

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        AppLocaleManager.initialize(this)

        // A process killed during a large import cannot run ImportBuffer.close(). Remove only
        // old import artifacts; recent files may still belong to another active process.
        ImportBuffer.cleanupStaleFiles(cacheDir)

        WorkManager.initialize(this, buildWorkManagerConfiguration())

        // Storage bootstrap runs off the main thread and is retryable. The barrier opens only
        // when bootstrapStorage() returns normally; services, receivers and the UI wait on it.
        val isMain = isMainProcess()
        StorageBootstrap.install(appScope) { bootstrapStorage(isMain) }
    }

    /**
     * Order is load-bearing: integrity check first, settings snapshot second, default seeding
     * last. Every step is idempotent, so a retry simply runs the whole chain again.
     */
    private suspend fun bootstrapStorage(isMain: Boolean) {
        try {
            DatabaseIntegrity.verifyOrThrow(this)
            settings.refresh()

            if (isMain) {
                settings.seedDefaults()
                SettingsManager.ensureRoutingRulesets(this)
                SettingsManager.ensureDefaultSubscription()
            }
        } finally {
            LogUtil.refreshLogLevel()
        }

        // Only reached on success. Neither step may fail the bootstrap after the data is ready.
        if (observerStarted.compareAndSet(false, true)) {
            settings.observe(appScope)
        }
        runCatching { ThemeManager.refresh() }
            .onFailure { LogUtil.e(AppConfig.TAG, "Theme refresh failed", it) }
    }

    private fun isMainProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName() == packageName
        }
        val pid = android.os.Process.myPid()
        val am = getSystemService(ActivityManager::class.java) ?: return true
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName == packageName
    }

    /**
     * Built here rather than in a property initialiser: a property would be evaluated during
     * construction, before Hilt has injected [workerFactory].
     */
    private fun buildWorkManagerConfiguration(): Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .setWorkerFactory(workerFactory)
        .build()
}
