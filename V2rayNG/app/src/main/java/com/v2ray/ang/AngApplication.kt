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
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        AppLocaleManager.initialize(this)

        WorkManager.initialize(this, buildWorkManagerConfiguration())

        // Storage bootstrap runs off the main thread; the order is load-bearing:
        // integrity check + legacy import first, settings snapshot second, default seeding last.
        // StorageBootstrap only opens when all of it succeeded — services, receivers and the UI
        // wait on it instead of racing this coroutine and acting on coded defaults.
        appScope.launch {
            val isMain = isMainProcess()
            var storageReady = false
            try {
                // Every process goes through the gate first. :daemon can easily start before
                // the UI process (Always-on VPN / boot broadcast / Tile / Glance widget), and
                // an empty database plus coded defaults means SELECTED_SERVER is null and the
                // core has no profile to start. The file lock makes the concurrent path safe;
                // when the import has already run, the cost is one settings primary-key read.
                check(LegacyMigrationGate.runIfNeeded(this@AngApplication, db, settings, io)) {
                    "Legacy import did not finish; will retry next launch"
                }

                settings.refresh()

                // Seeding is main-process only: these are idempotent writes, and running them
                // in every process buys nothing but write-lock contention against the import.
                // Seeding also stays behind a finished migration: rows written after a failed
                // import would count as pre-existing on the retry.
                if (isMain) {
                    settings.seedDefaults()
                    SettingsManager.ensureRoutingRulesets(this@AngApplication)
                    SettingsManager.ensureDefaultSubscription()
                }

                storageReady = true
                StorageBootstrap.complete()
            } catch (e: CancellationException) {
                StorageBootstrap.fail(e)
                throw e
            } catch (e: Exception) {
                StorageBootstrap.fail(e)
                LogUtil.e(AppConfig.TAG, "Storage bootstrap failed; waiting callers stay blocked", e)
            } finally {
                LogUtil.refreshLogLevel()
            }

            // Diagnostics run either way; a failure benefits from them the most.
            runCatching { LegacyMigrationGate.logStorageMode(this@AngApplication, db, isMain) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Storage mode logging failed", it) }

            // Only a settled storage layer may observe further invalidations or read the theme;
            // on failure the process keeps the barrier closed until the next app start.
            if (storageReady) {
                settings.observe(appScope)
                ThemeManager.refresh()
            }
        }
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
