package com.v2ray.ang.data

import com.v2ray.ang.AppConfig

/**
 * Coded defaults written once at first launch, replacing
 * SettingsManager.ensureDefaultSettings(). Order is preserved from the previous
 * implementation; every value is stored as text, matching the settings table contract.
 *
 * Only list a key here when the default must be visible in the settings UI. A default that is
 * only consumed in code belongs at the read site as the `default` argument.
 */
internal object SettingsDefaults {

    data class Entry(val key: String, val value: String, val kind: String = SettingsStore.KIND_STRING)

    val ENTRIES: List<Entry> = listOf(
        Entry(AppConfig.PREF_MODE, AppConfig.VPN),
        Entry(AppConfig.PREF_VPN_DNS, AppConfig.DNS_VPN),
        Entry(AppConfig.PREF_VPN_MTU, AppConfig.VPN_MTU.toString()),
        Entry(AppConfig.PREF_SOCKS_PORT, AppConfig.PORT_SOCKS),
        Entry(AppConfig.PREF_REMOTE_DNS, AppConfig.DNS_PROXY),
        Entry(AppConfig.PREF_DOMESTIC_DNS, AppConfig.DNS_DIRECT),
        Entry(AppConfig.PREF_DELAY_TEST_URL, AppConfig.DELAY_TEST_URL),
        Entry(AppConfig.PREF_IP_API_URL, AppConfig.IP_API_URL),
        Entry(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT, AppConfig.HEVTUN_RW_TIMEOUT),
        Entry(AppConfig.PREF_MUX_CONCURRENCY, "8"),
        Entry(AppConfig.PREF_MUX_XUDP_CONCURRENCY, "8"),
        Entry(AppConfig.PREF_FRAGMENT_LENGTH, "50-100"),
        Entry(AppConfig.PREF_FRAGMENT_INTERVAL, "10-20"),
        Entry(AppConfig.PREF_FRAGMENT_MAXSPLIT, "10"),
        Entry(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL),
        Entry(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL),
        Entry(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD),
        Entry(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING, AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING),
        Entry(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT),
    )
}
