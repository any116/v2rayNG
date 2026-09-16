package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil

/**
 * Head of the core startup sequence.
 *
 * Refreshes the preference snapshot for the calling process, then seeds the routing rulesets if
 * this process is the first to open the database (boot autostart with the UI never opened). After
 * this returns, every Prefs.* read in the core build path is warm and no database access is needed
 * to stay synchronous.
 *
 * Deliberately does not call SettingsStore.seedDefaults(): AngApplication already does that on
 * every process start, and repeating it here would write settings rows from the daemon.
 */
internal object CoreStartup {

    suspend fun refreshPreferences(context: Context) {
        try {
            PlatformDependencies.settingsStore(context).refresh()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "CoreStartup: failed to refresh preferences", e)
        }
        // Randomise the dynamic SOCKS port once per core launch.
        runCatching { SettingsManager.refreshRuntimeSocksPort() }
            .onFailure { LogUtil.e(AppConfig.TAG, "CoreStartup: failed to refresh runtime socks port", it) }
        runCatching { SettingsManager.ensureRoutingRulesets(context) }
            .onFailure { LogUtil.e(AppConfig.TAG, "CoreStartup: failed to seed routing rulesets", it) }
    }
}
