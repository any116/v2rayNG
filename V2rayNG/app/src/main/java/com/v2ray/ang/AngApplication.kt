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
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.HiltAndroidApp
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
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        // Settings bootstrap runs off the main thread.
        appScope.launch {
            runCatching {
                settings.refresh()
                settings.seedDefaults()
            }.onFailure {
                LogUtil.e(AppConfig.TAG, "Settings bootstrap failed", it)
                return@launch
            }

            // Routing presets need the database; the call is idempotent and also runs at the
            // head of the core startup sequence.
            runCatching { SettingsManager.ensureRoutingRulesets(this@AngApplication) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Routing ruleset seeding failed", it) }

            settings.observe(appScope)
            ThemeManager.refresh()
        }

        AppLocaleManager.initialize(this)

        WorkManager.initialize(this, buildWorkManagerConfiguration())
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
