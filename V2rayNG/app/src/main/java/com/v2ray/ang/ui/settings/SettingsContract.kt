package com.v2ray.ang.ui.settings

import androidx.compose.runtime.Immutable
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.data.repository.BoolPref
import com.v2ray.ang.data.repository.StringPref
import com.v2ray.ang.ui.base.BaseAction
import com.v2ray.ang.ui.base.BaseEvent
import com.v2ray.ang.ui.base.BaseUiState

@Immutable
data class SettingsUiState(
    val bools: Map<BoolPref, Boolean> = emptyMap(),
    val strings: Map<StringPref, String> = emptyMap(),
    /** Any preference changed, so the caller is notified on exit. */
    val changed: Boolean = false,
    /** At least one changed preference is not UI-only, so the core must restart. */
    val restartService: Boolean = false,
    /** Dynamic color support and current state (read from ThemeRepository) */
    val dynamicColorSupported: Boolean = false,
) : BaseUiState {

    operator fun get(pref: BoolPref): Boolean = bools[pref] ?: pref.default
    operator fun get(pref: StringPref): String = strings[pref] ?: pref.default

    val loaded: Boolean get() = bools.isNotEmpty()

    val isVpn: Boolean get() = get(StringPref.MODE) == VPN
    val hevTunnel: Boolean get() = isVpn && get(BoolPref.USE_HEV_TUNNEL)

    val rootProxy: Boolean
        get() = get(BoolPref.ROOT_MODE_ENABLE) || get(BoolPref.ROOT_LAN_SHARING)

    /**
     * Kept in sync with CoreConfigManager.configureInbounds()'s `forcedByHev ||
     * forcedBySocksRoot`. It used to be `hevTunnel` only, so with root mode on the menu let the
     * user switch the local proxy off while the core kept building the inbound anyway.
     */
    val localProxyForced: Boolean get() = hevTunnel || rootProxy
    val localProxy: Boolean get() = get(BoolPref.ENABLE_LOCAL_PROXY) || localProxyForced

    /**
     * Same condition, separate name because the reason differs: hev-socks5-tunnel only speaks
     * standard SOCKS5 UDP ASSOCIATE, so a SOCKS inbound with "udp": false silently drops every
     * UDP packet it forwards — DNS included, which looks like "connected but nothing loads".
     */
    val socksUdpForced: Boolean get() = localProxyForced
    val socksUdp: Boolean get() = get(BoolPref.SOCKS_ENABLE_UDP) || socksUdpForced

    val xudpQuicEnabled: Boolean
        get() = get(BoolPref.MUX_ENABLED) &&
            (get(StringPref.MUX_XUDP_CONCURRENCY).toIntOrNull() ?: 8) >= 0
}

sealed interface SettingsAction : BaseAction {
    data object Back : SettingsAction
    data class BoolChanged(val pref: BoolPref, val value: Boolean) : SettingsAction
    data class TextChanged(val pref: StringPref, val value: String) : SettingsAction
    data object ModeHelpClicked : SettingsAction
}

sealed interface SettingsEvent : BaseEvent.Platform {
    /** AppCompat switches the per-app locale on the main thread and recreates the Activity. */
    data class ApplyLanguage(val code: String) : SettingsEvent

    /** Recreates the host after the setting write so the back dispatcher reads the new value. */
    data object PredictiveBackChanged : SettingsEvent
}
