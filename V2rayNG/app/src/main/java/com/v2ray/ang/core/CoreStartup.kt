package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException

/**
 * Head of the core startup sequence.
 *
 * Waits (bounded) for this process' [StorageBootstrap], then performs a strong-consistency
 * settings refresh. Reading before the integrity check / legacy import finished would hand the
 * core a snapshot of coded defaults, and a genuine refresh failure must abort the start instead
 * of running with defaults (mode, ports, routing). After a successful return, every Prefs.* read
 * in the core build path is warm and no database access is needed to stay synchronous.
 *
 * A failed bootstrap is retried once per core start, so a transient failure in :daemon no longer
 * requires a process restart; the wait itself is bounded, because the file lock inside the
 * migration gate can stay held while another process is still importing and the foreground
 * service must not sit in "starting" forever.
 *
 * Deliberately does not call SettingsStore.seedDefaults(): AngApplication already does that on
 * every process start, and repeating it here would write settings rows from the daemon.
 *
 * @return false when the storage layer is not usable; the caller must not start the core.
 */
internal object CoreStartup {

    /** The foreground notification is already up; this only bounds a stuck file lock / import. */
    private const val STORAGE_READY_TIMEOUT_MS = 30_000L

    suspend fun refreshPreferences(context: Context): Boolean {
        StorageBootstrap.retry()
        if (!StorageBootstrap.awaitReadyOrNull(STORAGE_READY_TIMEOUT_MS)) {
            LogUtil.e(AppConfig.TAG, "CoreStartup: storage not ready; core start aborted")
            return false
        }
        try {
            PlatformDependencies.settingsStore(context).refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "CoreStartup: settings refresh failed; core start aborted", e)
            return false
        }
        // Randomise the dynamic SOCKS port once per core launch.
        runCatching { SettingsManager.refreshRuntimeSocksPort() }
            .onFailure { LogUtil.e(AppConfig.TAG, "CoreStartup: failed to refresh runtime socks port", it) }
        runCatching { SettingsManager.ensureRoutingRulesets(context) }
            .onFailure { LogUtil.e(AppConfig.TAG, "CoreStartup: failed to seed routing rulesets", it) }
        return true
    }
}
