package com.v2ray.ang.handler

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.enums.Language
import com.v2ray.ang.util.LogUtil

/**
 * Keeps the legacy in-app language preference synchronized with Android's per-app locale APIs.
 */
object AppLocaleManager {

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.prepareMigration(context)
        } else {
            initializeCompat(context)
        }
    }

    /** Completes the one-time handoff after AppCompat has attached the activity context. */
    fun onActivityCreated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.completeMigration(context)
        } else {
            syncLegacyPreference(AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag())
        }
    }

    fun setApplicationLanguage(languageCode: String) {
        val language = Language.fromCode(languageCode)
        persistLegacyPreference(language)
        AppCompatDelegate.setApplicationLocales(language.toLocaleList())
    }

    /** Returns a context suitable for resource access outside AppCompatActivity. */
    fun localizedContext(context: Context): Context {
        val localizedContext = ContextCompat.getContextForLanguage(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || localizedContext !== context) {
            return localizedContext
        }

        // During the one-time upgrade from the old picker, AppCompat has not written its locale
        // record yet. Use the still-current stored value for non-activity contexts on that launch.
        val language = storedLanguage()
        language.locale ?: return context
        val configuration = Configuration(context.resources.configuration)
        ConfigurationCompat.setLocales(configuration, language.toLocaleList())
        return context.createConfigurationContext(configuration)
    }

    private fun initializeCompat(context: Context) {
        val storedLocales = LocaleManagerCompat.getApplicationLocales(context)
        if (storedLocales.isEmpty) {
            val legacyLanguage = storedLanguage()
            if (legacyLanguage != Language.AUTO) {
                AppCompatDelegate.setApplicationLocales(legacyLanguage.toLocaleList())
            }
        } else {
            syncLegacyPreference(storedLocales.get(0)?.toLanguageTag())
        }
    }

    private fun storedLanguage(): Language =
        Language.fromCode(Prefs.string(AppConfig.PREF_LANGUAGE) ?: Language.AUTO.code)

    private fun syncLegacyPreference(languageTag: String?) {
        persistLegacyPreference(Language.fromLanguageTag(languageTag))
    }

    private fun persistLegacyPreference(language: Language) {
        // An unready snapshot reads as absent, which resolves to AUTO. Persisting that would
        // destroy the real preference, so skip the write instead.
        if (!Prefs.isReady) {
            LogUtil.w(AppConfig.TAG, "Skipped locale write, settings snapshot not ready")
            return
        }
        if (Prefs.string(AppConfig.PREF_LANGUAGE) == language.code) return
        Prefs.setString(AppConfig.PREF_LANGUAGE, language.code)
        SettingsChangeManager.notifySettingChanged(AppConfig.PREF_LANGUAGE)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun prepareMigration(context: Context) {
            if (Prefs.bool(AppConfig.PREF_APP_LOCALE_MIGRATED, false)) return

            val localeManager = context.getSystemService(LocaleManager::class.java)
            val applicationLocales = localeManager.applicationLocales
            if (!applicationLocales.isEmpty) {
                syncLegacyPreference(applicationLocales.get(0)?.toLanguageTag())
                return
            }

            val legacyLanguage = storedLanguage()
            if (legacyLanguage != Language.AUTO) {
                // Make application and service contexts correct even before the first activity.
                localeManager.applicationLocales = android.os.LocaleList(
                    requireNotNull(legacyLanguage.locale)
                )
            }
        }

        fun completeMigration(context: Context) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (!Prefs.bool(AppConfig.PREF_APP_LOCALE_MIGRATED, false)) {
                val frameworkLanguage = Language.fromLanguageTag(
                    localeManager.applicationLocales.get(0)?.toLanguageTag()
                )
                val language = frameworkLanguage.takeUnless { it == Language.AUTO }
                    ?: storedLanguage()

                // Android's migration guidance requires this one-time AppCompat handoff after
                // Activity.onCreate(), including on Android 13 and newer.
                AppCompatDelegate.setApplicationLocales(language.toLocaleList())
                persistLegacyPreference(language)
                Prefs.setBool(AppConfig.PREF_APP_LOCALE_MIGRATED, true)
                return
            }

            syncLegacyPreference(localeManager.applicationLocales.get(0)?.toLanguageTag())
        }
    }
}
