package com.v2ray.ang.data.repository

import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.handler.UpdateCheckerManager
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

open class CheckUpdateRepository @Inject constructor(
    private val settings: SettingsStore,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    open fun isCheckPreRelease(): Boolean =
        Prefs.bool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

    /**
     * Persists the flag and reads it back, the caller reflects what is actually stored rather
     * than what the UI event claimed.
     *
     * @return the persisted value
     */
    open suspend fun setCheckPreRelease(enabled: Boolean): Boolean = withIO {
        settings.putBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled)
        Prefs.bool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
    }

    open fun appVersionText(): String = "$VERSION_PREFIX${BuildConfig.VERSION_NAME}"

    open suspend fun fullVersionText(): String = withIO {
        "$VERSION_PREFIX${BuildConfig.VERSION_NAME} (${CoreNativeManager.getLibVersion()})"
    }

    open suspend fun checkForUpdate(includePreRelease: Boolean): CheckUpdateResult =
        withIO { UpdateCheckerManager.checkForUpdate(includePreRelease) }

    private companion object {
        const val VERSION_PREFIX = "v"
    }
}
