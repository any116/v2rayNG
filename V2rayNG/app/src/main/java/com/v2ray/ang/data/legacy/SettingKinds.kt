package com.v2ray.ang.data.legacy

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.SettingsStore

/**
 * key -> kind registry for the legacy import.
 *
 * MMKV stores no type information, so the importer cannot derive `kind` from the store. This
 * table is the only place that knows it, which makes it a correctness-critical list: a boolean
 * decoded as a string imports as null and the preference silently reverts to its default.
 * MmkvImportTest asserts every AppConfig PREF_* / CACHE_* constant is either listed here or
 * deliberately a string.
 */
internal object SettingKinds {

    fun of(key: String): String = KINDS[key] ?: SettingsStore.KIND_STRING

    private val BOOLEAN_KEYS = setOf(
        AppConfig.PREF_SNIFFING_ENABLED,
        AppConfig.PREF_ROUTE_ONLY_ENABLED,
        AppConfig.PREF_PER_APP_PROXY,
        AppConfig.PREF_BYPASS_APPS,
        AppConfig.PREF_LOCAL_DNS_ENABLED,
        AppConfig.PREF_FAKE_DNS_ENABLED,
        AppConfig.PREF_APPEND_HTTP_PROXY,
        AppConfig.PREF_MUX_ENABLED,
        AppConfig.PREF_MUX_XUDP_QUIC,
        AppConfig.PREF_FRAGMENT_ENABLED,
        AppConfig.PREF_SPEED_ENABLED,
        AppConfig.PREF_CONFIRM_REMOVE,
        AppConfig.PREF_DOUBLE_COLUMN_DISPLAY,
        AppConfig.PREF_GROUP_ALL_DISPLAY,
        AppConfig.PREF_APP_LOCALE_MIGRATED,
        AppConfig.PREF_DYNAMIC_COLOR,
        AppConfig.PREF_IPV6_ENABLED,
        AppConfig.PREF_PREFER_IPV6,
        AppConfig.PREF_PROXY_SHARING,
        AppConfig.PREF_ENABLE_LOCAL_PROXY,
        AppConfig.PREF_DYNAMIC_SOCKS_PORT,
        AppConfig.PREF_SOCKS_ENABLE_UDP,
        AppConfig.PREF_ROOT_MODE_ENABLE,
        AppConfig.PREF_ROOT_LAN_SHARING,
        AppConfig.PREF_IS_BOOTED,
        AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE,
        AppConfig.PREF_USE_HEV_TUNNEL,
        AppConfig.PREF_UPDATE_SUBSCRIPTION,
        AppConfig.PREF_AUTO_TEST_AFTER_UPDATE_SUBSCRIPTION,
        AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST,
        AppConfig.PREF_AUTO_SORT_AFTER_TEST,
    )

    private val LONG_KEYS = setOf(
        AppConfig.CACHE_LOGCAT_CLEARED_AT,
        AppConfig.CACHE_WIDGET_STATE_AT,
    )

    private val INT_KEYS = setOf(
        AppConfig.CACHE_WORKER_SCHEMA_VERSION,
    )

    private val SET_KEYS = setOf(
        AppConfig.PREF_PER_APP_PROXY_SET,
    )

    private val KINDS: Map<String, String> = buildMap {
        BOOLEAN_KEYS.forEach { put(it, SettingsStore.KIND_BOOL) }
        LONG_KEYS.forEach { put(it, SettingsStore.KIND_LONG) }
        INT_KEYS.forEach { put(it, SettingsStore.KIND_INT) }
        SET_KEYS.forEach { put(it, SettingsStore.KIND_SET) }
    }
}
