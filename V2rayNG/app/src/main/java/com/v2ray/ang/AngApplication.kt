package com.v2ray.ang

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager
import dagger.hilt.android.HiltAndroidApp
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

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        // Hilt field injection runs inside this call; workerFactory is unusable before it returns.
        super.onCreate()

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, buildWorkManagerConfiguration())

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Initialize theme state from MMKV
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
