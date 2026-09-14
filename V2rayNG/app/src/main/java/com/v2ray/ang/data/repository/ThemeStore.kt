package com.v2ray.ang.data.repository

import com.v2ray.ang.enums.AppThemeMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner of the process-wide theme state. Reads are snapshot reads; writes come in two flavours
 * because the settings screen writes from a suspend chain while the Compose facade does not.
 */
interface ThemeStore {

    /** Monet dynamic color exists only from Android 12 on. */
    val isDynamicColorSupported: Boolean

    val themeMode: StateFlow<AppThemeMode>

    val dynamicColorEnabled: StateFlow<Boolean>

    fun getThemeMode(): AppThemeMode

    fun isDynamicColorEnabled(): Boolean

    suspend fun setThemeMode(mode: AppThemeMode)

    suspend fun setDynamicColorEnabled(enabled: Boolean)

    /** Same as [setThemeMode], for non-suspend call sites. The StateFlow updates immediately. */
    fun setThemeModeAsync(mode: AppThemeMode)

    /** Same as [setDynamicColorEnabled], for non-suspend call sites. */
    fun setDynamicColorEnabledAsync(enabled: Boolean)

    /** Re-reads the settings snapshot. Called once during application bootstrap. */
    fun refresh()
}
