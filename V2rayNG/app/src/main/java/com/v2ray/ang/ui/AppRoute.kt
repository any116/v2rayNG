package com.v2ray.ang.ui

import androidx.annotation.StringRes
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.BaseRoute
import kotlinx.serialization.Serializable

/** Pure navigation targets; Android entry components are defined separately by [ExternalEntry]. */
sealed interface AppRoute : BaseRoute {
    @Serializable
    data object Main : AppRoute

    @Serializable
    data object SubSetting : AppRoute

    @Serializable
    data object PerAppProxy : AppRoute

    @Serializable
    data object RoutingSetting : AppRoute

    @Serializable
    data object UserAsset : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object Logcat : AppRoute

    @Serializable
    data object CheckUpdate : AppRoute

    @Serializable
    data object Backup : AppRoute

    @Serializable
    data object About : AppRoute

    @Serializable
    data object Scanner : AppRoute

    /** Profile protocol travels as its stable numeric value, not an Android component. */
    @Serializable
    data class ServerEdit(
        val configType: Int = EConfigType.VMESS.value,
        val guid: String = "",
        val subscriptionId: String = "",
        val isRunning: Boolean = false
    ) : AppRoute

    @Serializable
    data class RoutingEdit(val ruleId: String = "") : AppRoute

    @Serializable
    data class SubEdit(val subId: String = "") : AppRoute

    @Serializable
    data class UserAssetUrl(
        val assetId: String = "",
        val qrCodeUrl: String = ""
    ) : AppRoute

    @Serializable
    data class AppPicker(
        val selected: List<String> = emptyList(),
        @StringRes val titleRes: Int = 0
    ) : AppRoute

    /** Browser navigation is handled by the screen host, outside the application back stack. */
    data class OpenUrl(val url: String) : AppRoute

    companion object {
        /** Property names used when seeding SavedStateHandle arguments in tests. */
        const val EXTRA_TYPE = "configType"
        const val EXTRA_GUID = "guid"
        const val EXTRA_SUB_ID = "subscriptionId"
        const val EXTRA_RUNNING = "isRunning"
        const val EXTRA_RULE_ID = "ruleId"
        const val EXTRA_SUB_GUID = "subId"
        const val EXTRA_ASSET_ID = "assetId"
        const val EXTRA_ASSET_QRCODE = "qrCodeUrl"
        const val EXTRA_PICKER_SELECTED = "selected"
        const val EXTRA_PICKER_TITLE_RES = "titleRes"
    }
}
