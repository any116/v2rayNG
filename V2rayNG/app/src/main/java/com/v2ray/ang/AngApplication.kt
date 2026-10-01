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
import com.v2ray.ang.data.AppDatabase
import com.v2ray.ang.data.LegacyMigrationGate
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.di.ApplicationScope
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.components.ThemeManager
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineDispatcher
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
    lateinit var db: AppDatabase

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    /** The invalidation observer must be started once per process, not once per attempt. */
    private val observerStarted = AtomicBoolean(false)

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        AppLocaleManager.initialize(this)

        WorkManager.initialize(this, buildWorkManagerConfiguration())

        // Storage bootstrap runs off the main thread and is retryable. The barrier opens only
        // when bootstrapStorage() returns normally; services, receivers and the UI wait on it.
        val isMain = isMainProcess()
        StorageBootstrap.install(appScope) { bootstrapStorage(isMain) }
    }

    /**
     * Order is load-bearing: integrity check + legacy import first, settings snapshot second,
     * default seeding last. Every step is idempotent, so a retry simply runs the whole chain again.
     */
    private suspend fun bootstrapStorage(isMain: Boolean) {
        try {
            // Every process goes through the gate first. :daemon can start before the UI process
            // (Always-on VPN / boot broadcast / Tile / Glance widget). The file lock makes the
            // concurrent path safe; once imported, the cost is one settings primary-key read.
            check(LegacyMigrationGate.runIfNeeded(this, db, settings, io)) {
                "Legacy import did not finish"
            }

            settings.refresh()

            // Main process only, and only behind a finished migration: rows written after a
            // failed import would count as pre-existing on the retry.
            if (isMain) {
                settings.seedDefaults()
                SettingsManager.ensureRoutingRulesets(this)
                SettingsManager.ensureDefaultSubscription()
            }
        } finally {
            LogUtil.refreshLogLevel()
            // Diagnostics run either way; a failure benefits from them the most.
            runCatching { LegacyMigrationGate.logStorageMode(this, db, isMain) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Storage mode logging failed", it) }
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
