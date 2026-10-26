package com.v2ray.ang.handler

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.enums.Language
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Keeps the in-app language preference synchronized with Android's per-app locale APIs.
 */
object AppLocaleManager {

    // Scope for bridging suspend functions to synchronous lifecycle/UI callbacks.
    private val scope: CoroutineScope = MainScope()
    private var initializationJob: Job? = null

    fun initialize(context: Context) {
        initializationJob = scope.launch {
            if (!StorageBootstrap.awaitReadyOrNull()) return@launch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Api33.initialize(context)
            }
        }
    }

    /** Synchronizes the persisted locale after a BaseActivity has rendered its first frame. */
    suspend fun prepareActivity(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                initializationJob?.join()
                if (Prefs.isReady) {
                    syncLegacyPreference(Api33.applicationLanguage(context).languageTag)
                }
            } else {
                initializationJob?.join()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            LogUtil.e(AppConfig.TAG, "Failed to prepare application locale", t)
        }
    }

    fun setApplicationLanguage(context: Context, languageCode: String) {
        val language = Language.fromCode(languageCode)
        scope.launch {
            persistLegacyPreference(language)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Api33.setApplicationLanguage(context, language)
            }
            MessageHelper.sendMsg2Service(context, AppConfig.MSG_LOCALE_CHANGED, language.code)
            MessageHelper.sendMsg2UI(context, AppConfig.MSG_LOCALE_CHANGED, language.code)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) restartApplication(context)
        }
    }

    /** Returns a context using the selected application language for non-Activity components. */
    fun localizedContext(context: Context): Context =
        if (context is LocaleContextWrapper) context else LocaleContextWrapper(context)

    /** Returns a context using an explicit language while a cross-process locale update propagates. */
    fun localizedContext(context: Context, languageCode: String): Context =
        LocaleContextWrapper(context, Language.fromCode(languageCode))

    private fun storedLanguage(): Language =
        Language.fromCode(Prefs.string(AppConfig.PREF_LANGUAGE) ?: Language.AUTO.code)

    private fun Language.toAndroidLocaleList(): LocaleList =
        locale?.let { LocaleList(it) } ?: LocaleList.getEmptyLocaleList()

    private suspend fun syncLegacyPreference(languageTag: String?) {
        persistLegacyPreference(Language.fromLanguageTag(languageTag))
    }

    private suspend fun persistLegacyPreference(language: Language) {
        if (!Prefs.isReady && !StorageBootstrap.awaitReadyOrNull()) return
        if (!Prefs.isReady) return
        if (Prefs.string(AppConfig.PREF_LANGUAGE) == language.code) return
        Prefs.putString(AppConfig.PREF_LANGUAGE, language.code)
        SettingsChangeManager.notifySettingChanged(AppConfig.PREF_LANGUAGE)
    }

    private fun restartApplication(context: Context) {
        val component = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.component
        if (component == null) {
            LogUtil.e(AppConfig.TAG, "Unable to restart application: launch activity not found")
            return
        }

        context.startActivity(Intent.makeRestartActivityTask(component))
        // Do not remove the task here: the restart intent already clears/builds the new task, and
        // removing it synchronously can race ActivityManager and discard the freshly launched root.
        findActivity(context)?.finish()
    }

    private fun findActivity(context: Context): Activity? =
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()

    private fun resolveLanguage(context: Context): Language =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.applicationLanguage(context)
        } else {
            storedLanguage()
        }

    /**
     * Defers the low-version locale lookup until resources are actually requested. The Room-backed
     * settings snapshot is not guaranteed to be ready during attachBaseContext(), so resolving it
     * eagerly there would permanently bind a cold-start component to AUTO.
     */
    private class LocaleContextWrapper(
        base: Context,
        private val languageOverride: Language? = null,
    ) : ContextWrapper(base) {
        @Volatile
        private var cachedLanguage: Language? = null

        @Volatile
        private var cachedResources: Resources? = null

        @Volatile
        private var cachedConfiguration: Configuration? = null

        @Volatile
        private var cachedThemeResources: Resources? = null

        @Volatile
        private var cachedTheme: Resources.Theme? = null

        override fun getResources(): Resources {
            if (
                languageOverride == null &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                !Prefs.isReady
            ) {
                return baseContext.resources
            }

            val language = languageOverride ?: AppLocaleManager.resolveLanguage(baseContext)
            if (language == Language.AUTO) {
                cachedLanguage = null
                cachedResources = null
                cachedConfiguration = null
                return baseContext.resources
            }

            val configuration = Configuration(baseContext.resources.configuration)
            configuration.setLocales(language.toAndroidLocaleList())

            val currentResources = cachedResources
            if (
                cachedLanguage == language &&
                cachedConfiguration == configuration &&
                currentResources != null
            ) {
                return currentResources
            }

            val localizedResources = baseContext
                .createConfigurationContext(configuration)
                .resources
            cachedLanguage = language
            cachedResources = localizedResources
            cachedConfiguration = Configuration(configuration)
            return localizedResources
        }

        /**
         * Context's string/color helpers are final and resolve through [getResources]. Assets and
         * themes, however, are delegated by ContextWrapper, so keep them paired with the
         * localized Resources instance as well.
         */
        override fun getAssets(): AssetManager = getResources().assets

        override fun getTheme(): Resources.Theme {
            val resources = getResources()
            if (resources === baseContext.resources) return baseContext.theme

            val currentTheme = cachedTheme
            if (cachedThemeResources === resources && currentTheme != null) return currentTheme

            return resources.newTheme().also { theme ->
                theme.setTo(baseContext.theme)
                cachedThemeResources = resources
                cachedTheme = theme
            }
        }

        override fun setTheme(resid: Int) {
            baseContext.setTheme(resid)
            cachedThemeResources = null
            cachedTheme = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {

        suspend fun initialize(context: Context) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val applicationLocales = localeManager.applicationLocales
            val hasFrameworkLocale = !applicationLocales.isEmpty
            val frameworkLanguage = if (hasFrameworkLocale) {
                Language.fromLanguageTag(applicationLocales.get(0)?.toLanguageTag())
            } else {
                Language.AUTO
            }
            if (Prefs.bool(AppConfig.PREF_APP_LOCALE_MIGRATED, false)) {
                // An empty framework list is the explicit AUTO state; keep the legacy setting in
                // sync instead of resurrecting the previous language on every cold start.
                syncLegacyPreference(frameworkLanguage.languageTag)
                return
            }

            val language = frameworkLanguage.takeUnless { it == Language.AUTO }
                ?: storedLanguage()
            if (!hasFrameworkLocale && language != Language.AUTO) {
                // Migrate the pre-API-33 preference only when the framework has no app override.
                localeManager.applicationLocales = language.toAndroidLocaleList()
            }
            persistLegacyPreference(language)
            Prefs.putBool(AppConfig.PREF_APP_LOCALE_MIGRATED, true)
        }

        fun applicationLanguage(context: Context): Language {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val applicationLocales = localeManager.applicationLocales
            return if (applicationLocales.isEmpty) {
                Language.AUTO
            } else {
                Language.fromLanguageTag(applicationLocales.get(0)?.toLanguageTag())
            }
        }

        fun setApplicationLanguage(context: Context, language: Language) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = language.toAndroidLocaleList()
        }
    }
}
