package com.v2ray.ang

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.di.ApplicationScope
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Factory for @HiltWorker workers. Field injection completes inside super.onCreate(), so this
     * must never be touched from attachBaseContext or from a property initialiser.
     *
     * Every process gets its own Application and its own Hilt graph, so every process gets its own
     * factory. That is exactly what the `:bg` worker process needs.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settings: SettingsStore

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    override fun onCreate() {
        // Hilt field injection runs inside this call; injected fields are unusable before it returns.
        super.onCreate()

        // Still required: MmkvLegacyReader opens the legacy stores during the first database
        // creation, which can happen on any thread in any process. Moves into the reader itself
        // once the MMKV dependency is dropped.
        MmkvManager.initialize(this)

        // The only runBlocking in the project. It replaces the previous MMKV mmap and is one
        // SELECT over a few dozen rows. It must never read profiles.
        runBlocking {
            settings.refresh()
            settings.seedDefaults()
        }
        settings.observe(appScope)

        AppLocaleManager.initialize(this)

        WorkManager.initialize(this, buildWorkManagerConfiguration())

        // Routing presets need the database, so they are seeded off the main thread. The call is
        // idempotent and also runs at the head of the core startup sequence.
        appScope.launch { SettingsManager.ensureRoutingRulesets(this@AngApplication) }

        ThemeManager.refresh()
    }

    /**
     * Built here rather than in a property initialiser: a property would be evaluated during
     * construction, before Hilt has injected [workerFactory].
     *
     * Manual initialization is kept deliberately (migration plan section 10.2). The manifest still
     * removes WorkManagerInitializer and declares RemoteWorkManagerService in `:bg`, and this
     * remains the single initialization path — Configuration.Provider is not implemented, so the
     * two mechanisms cannot both be live.
     *
     * setWorkerFactory does not break plain workers: WorkManager calls the factory through
     * createWorkerWithDefaultFallback, which falls back to reflection whenever
     * [HiltWorkerFactory] returns null for a class that is not annotated with @HiltWorker.
     */
    private fun buildWorkManagerConfiguration(): Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .setWorkerFactory(workerFactory)
        .build()
}
