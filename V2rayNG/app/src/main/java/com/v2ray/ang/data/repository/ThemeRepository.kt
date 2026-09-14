package com.v2ray.ang.data.repository

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.enums.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeRepository @Inject constructor(
    private val settings: SettingsStore,
) : ThemeStore {

    override val isDynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val _themeMode = MutableStateFlow(AppThemeMode.System)
    override val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false)
    override val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    init {
        refresh()
    }

    override fun getThemeMode(): AppThemeMode = _themeMode.value

    override fun isDynamicColorEnabled(): Boolean = _dynamicColorEnabled.value

    override suspend fun setThemeMode(mode: AppThemeMode) {
        if (!applyThemeMode(mode)) return
        settings.putString(AppConfig.PREF_UI_MODE_NIGHT, mode.value)
    }

    override fun setThemeModeAsync(mode: AppThemeMode) {
        if (!applyThemeMode(mode)) return
        settings.setStringAsync(AppConfig.PREF_UI_MODE_NIGHT, mode.value)
    }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) {
        val resolved = resolveDynamicColor(enabled) ?: return
        settings.putBool(AppConfig.PREF_DYNAMIC_COLOR, resolved)
    }

    override fun setDynamicColorEnabledAsync(enabled: Boolean) {
        val resolved = resolveDynamicColor(enabled) ?: return
        settings.setBoolAsync(AppConfig.PREF_DYNAMIC_COLOR, resolved)
    }

    override fun refresh() {
        _themeMode.value = readThemeMode()
        _dynamicColorEnabled.value = readDynamicColorEnabled()
    }

    /** @return false when the value is unchanged, so the caller skips the write. */
    private fun applyThemeMode(mode: AppThemeMode): Boolean {
        if (_themeMode.value == mode) return false
        _themeMode.value = mode
        return true
    }

    /** @return the coerced value to persist, or null when nothing changed. */
    private fun resolveDynamicColor(enabled: Boolean): Boolean? {
        val resolved = enabled && isDynamicColorSupported
        if (_dynamicColorEnabled.value == resolved) return null
        _dynamicColorEnabled.value = resolved
        return resolved
    }

    private fun readThemeMode(): AppThemeMode = runCatching {
        AppThemeMode.from(
            settings.string(AppConfig.PREF_UI_MODE_NIGHT, AppThemeMode.System.value)
        )
    }.getOrDefault(AppThemeMode.System)

    private fun readDynamicColorEnabled(): Boolean {
        if (!isDynamicColorSupported) return false
        return settings.bool(AppConfig.PREF_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)
    }

    private companion object {
        const val DEFAULT_DYNAMIC_COLOR = true
    }
}
