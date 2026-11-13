package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.about.AboutActivity
import com.v2ray.ang.ui.apppicker.AppPickerActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.BaseRoute
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingEditActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.scanner.ScannerActivity
import com.v2ray.ang.ui.server.ServerEditActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubEditActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.ui.userasset.UserAssetUrlActivity
import kotlinx.serialization.Serializable

/**
 * Every navigation target of the app, and the single owner of the route argument keys.
 *
 * Application-internal destinations are `@Serializable` classes: `AppNavigation.kt` derives the
 * Navigation route pattern from them, so a property name *is* the argument name and no second
 * declaration of it is allowed. The compatibility [intent] implementations remain for external
 * entry points and legacy Activity wrappers; they write their extras under those same names, which
 * keeps the `SavedStateHandle` of a Compose destination and of a wrapper Activity identical.
 * Screens read arguments through the keys in [AppRoute.Companion] rather than re-typing literals.
 */
sealed interface AppRoute : BaseRoute {

    /** Home screen; used by entry points that hand control back to the app. */
    @Serializable
    data object Main : AppRoute {
        override fun intent(context: Context) = Intent(context, MainActivity::class.java)
    }

    /** Subscription list. */
    @Serializable
    data object SubSetting : AppRoute {
        override fun intent(context: Context) = Intent(context, SubSettingActivity::class.java)
    }

    /** Per-app proxy configuration. */
    @Serializable
    data object PerAppProxy : AppRoute {
        override fun intent(context: Context) = Intent(context, PerAppProxyActivity::class.java)
    }

    /** Routing ruleset list. */
    @Serializable
    data object RoutingSetting : AppRoute {
        override fun intent(context: Context) = Intent(context, RoutingSettingActivity::class.java)
    }

    /** Geo/user asset management. */
    @Serializable
    data object UserAsset : AppRoute {
        override fun intent(context: Context) = Intent(context, UserAssetActivity::class.java)
    }

    /** App settings. */
    @Serializable
    data object Settings : AppRoute {
        override fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }

    /** Log viewer. */
    @Serializable
    data object Logcat : AppRoute {
        override fun intent(context: Context) = Intent(context, LogcatActivity::class.java)
    }

    /** Update checker. */
    @Serializable
    data object CheckUpdate : AppRoute {
        override fun intent(context: Context) = Intent(context, CheckUpdateActivity::class.java)
    }

    /** Backup and restore. */
    @Serializable
    data object Backup : AppRoute {
        override fun intent(context: Context) = Intent(context, BackupActivity::class.java)
    }

    /** About screen. */
    @Serializable
    data object About : AppRoute {
        override fun intent(context: Context) = Intent(context, AboutActivity::class.java)
    }

    @Serializable
    data object Scanner : AppRoute {
        override fun intent(context: Context) = Intent(context, ScannerActivity::class.java)
    }

    /**
     * Profile editor. One route for all protocols: [configType] picks the concrete editor.
     *
     * Enums cannot be encoded into a route argument, so [EConfigType.value] travels as an int and
     * the receiver maps it back.
     */
    @Serializable
    data class ServerEdit(
        val configType: Int = EConfigType.VMESS.value,
        val guid: String = "",
        val subscriptionId: String = "",
        val isRunning: Boolean = false,
    ) : AppRoute {
        override fun intent(context: Context): Intent =
            Intent(context, ServerEditActivity::class.java)
                .putExtra(EXTRA_TYPE, configType)
                .putExtra(EXTRA_GUID, guid)
                .putExtra(EXTRA_SUB_ID, subscriptionId)
                .putExtra(EXTRA_RUNNING, isRunning)
    }

    /**
     * Routing rule editor. Empty [ruleId] creates a new rule.
     */
    @Serializable
    data class RoutingEdit(val ruleId: String = "") : AppRoute {
        override fun intent(context: Context) =
            Intent(context, RoutingEditActivity::class.java)
                .putExtra(EXTRA_RULE_ID, ruleId)
    }

    /** Subscription editor; empty [subId] creates a new subscription. */
    @Serializable
    data class SubEdit(val subId: String = "") : AppRoute {
        override fun intent(context: Context) =
            Intent(context, SubEditActivity::class.java)
                .putExtra(EXTRA_SUB_GUID, subId)
    }

    /**
     * Asset URL editor; empty [assetId] creates a new asset and [qrCodeUrl] pre-fills the form
     * when the entry came from a scan.
     */
    @Serializable
    data class UserAssetUrl(
        val assetId: String = "",
        val qrCodeUrl: String = "",
    ) : AppRoute {
        override fun intent(context: Context) =
            Intent(context, UserAssetUrlActivity::class.java)
                .putExtra(EXTRA_ASSET_ID, assetId)
                .putExtra(EXTRA_ASSET_QRCODE, qrCodeUrl)
    }

    /**
     * App picker; answers with [com.v2ray.ang.ui.base.BaseResult.Selected].
     *
     * @param selected package names checked on entry
     * @param titleRes title resource (0 = default), passed as an id so no layer resolves strings early
     */
    @Serializable
    data class AppPicker(
        val selected: List<String> = emptyList(),
        @StringRes val titleRes: Int = 0,
    ) : AppRoute {
        override fun intent(context: Context) =
            Intent(context, AppPickerActivity::class.java)
                // A String[] is what both Navigation's List<String> argument and toRoute() expect.
                .putExtra(EXTRA_PICKER_SELECTED, selected.toTypedArray())
                .putExtra(EXTRA_PICKER_TITLE_RES, titleRes)
    }

    /**
     * An external link. [intent] returns `null` on purpose: [com.v2ray.ang.ui.base.BaseScreen]
     * recognises this route and opens it in a browser instead of starting an Activity.
     */
    data class OpenUrl(val url: String) : AppRoute {
        override fun intent(context: Context): Intent? = null
    }

    companion object {
        /**
         * Argument keys. Each value repeats the name of the route property that carries it, because
         * Navigation takes the route pattern from the property names; the two must not diverge.
         */

        /** Profile type ([EConfigType.value]). */
        const val EXTRA_TYPE = "configType"

        /** Profile guid being edited. */
        const val EXTRA_GUID = "guid"

        /** Owning subscription/group id. */
        const val EXTRA_SUB_ID = "subscriptionId"

        /** Whether the edited profile is currently active. */
        const val EXTRA_RUNNING = "isRunning"

        /** Routing rule identifier. */
        const val EXTRA_RULE_ID = "ruleId"

        /** Subscription id being edited. */
        const val EXTRA_SUB_GUID = "subId"

        /** Asset id being edited. */
        const val EXTRA_ASSET_ID = "assetId"

        /** Asset URL captured from a QR code. */
        const val EXTRA_ASSET_QRCODE = "qrCodeUrl"

        /** Package names pre-selected in [AppPicker]. */
        const val EXTRA_PICKER_SELECTED = "selected"

        /** Title string resource id for [AppPicker]. */
        const val EXTRA_PICKER_TITLE_RES = "titleRes"
    }
}