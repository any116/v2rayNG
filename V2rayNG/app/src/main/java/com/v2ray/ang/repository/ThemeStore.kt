package com.v2ray.ang.repository

import com.v2ray.ang.enums.AppThemeMode
import kotlinx.coroutines.flow.StateFlow

interface ThemeStore {

    /** Monet dynamic color exists only from Android 12 on. */
    val isDynamicColorSupported: Boolean

    val themeMode: StateFlow<AppThemeMode>

    val dynamicColorEnabled: StateFlow<Boolean>

    fun getThemeMode(): AppThemeMode

    fun setThemeMode(mode: AppThemeMode)

    fun isDynamicColorEnabled(): Boolean

    fun setDynamicColorEnabled(enabled: Boolean)

    /** Re-reads MMKV. Called once during application bootstrap, after MMKV is initialised. */
    fun refresh()
}
