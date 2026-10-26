package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.v2ray.ang.R
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.ui.about.AboutScreen
import com.v2ray.ang.ui.about.AboutViewModel
import com.v2ray.ang.ui.apppicker.AppPickerScreen
import com.v2ray.ang.ui.apppicker.AppPickerViewModel
import com.v2ray.ang.ui.backup.BackupAction
import com.v2ray.ang.ui.backup.BackupEvent
import com.v2ray.ang.ui.backup.BackupScreen
import com.v2ray.ang.ui.backup.BackupViewModel
import com.v2ray.ang.ui.base.AppNavigator
import com.v2ray.ang.ui.base.BaseResult
import com.v2ray.ang.ui.base.BaseRoute
import com.v2ray.ang.ui.base.LocalAppNavigator
import com.v2ray.ang.ui.base.LocalPlatformActions
import com.v2ray.ang.ui.base.findActivity
import com.v2ray.ang.ui.base.toBaseResult
import com.v2ray.ang.ui.base.toIntent
import com.v2ray.ang.ui.checkupdate.CheckUpdateScreen
import com.v2ray.ang.ui.checkupdate.CheckUpdateViewModel
import com.v2ray.ang.ui.logcat.LogcatAction
import com.v2ray.ang.ui.logcat.LogcatEvent
import com.v2ray.ang.ui.logcat.LogcatScreen
import com.v2ray.ang.ui.logcat.LogcatViewModel
import com.v2ray.ang.ui.main.MainEvent
import com.v2ray.ang.ui.main.MainScreen
import com.v2ray.ang.ui.main.MainViewModel
import com.v2ray.ang.ui.perappproxy.PerAppProxyScreen
import com.v2ray.ang.ui.perappproxy.PerAppProxyViewModel
import com.v2ray.ang.ui.routing.RoutingAction
import com.v2ray.ang.ui.routing.RoutingEditScreen
import com.v2ray.ang.ui.routing.RoutingEditViewModel
import com.v2ray.ang.ui.routing.RoutingEvent
import com.v2ray.ang.ui.routing.RoutingSettingScreen
import com.v2ray.ang.ui.routing.RoutingSettingViewModel
import com.v2ray.ang.ui.scanner.ScannerAction
import com.v2ray.ang.ui.scanner.ScannerEvent
import com.v2ray.ang.ui.scanner.ScannerScreen
import com.v2ray.ang.ui.scanner.ScannerViewModel
import com.v2ray.ang.ui.server.ServerEditScreen
import com.v2ray.ang.ui.server.ServerEditViewModel
import com.v2ray.ang.ui.settings.SettingsEvent
import com.v2ray.ang.ui.settings.SettingsScreen
import com.v2ray.ang.ui.settings.SettingsViewModel
import com.v2ray.ang.ui.subscription.SubEditScreen
import com.v2ray.ang.ui.subscription.SubEditViewModel
import com.v2ray.ang.ui.subscription.SubSettingScreen
import com.v2ray.ang.ui.subscription.SubSettingViewModel
import com.v2ray.ang.ui.userasset.UserAssetScreen
import com.v2ray.ang.ui.userasset.UserAssetUrlScreen
import com.v2ray.ang.ui.userasset.UserAssetUrlViewModel
import com.v2ray.ang.ui.userasset.UserAssetViewModel
import com.v2ray.ang.util.ShareUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.roundToInt

private const val RESULT_KEY = "app_navigation_result"
private const val SELECTED_SEPARATOR = ","
private const val TransitionDurationMillis = 400
private const val TransitionOffsetFraction = 0.15f

private object NavRoute {
    const val MAIN = "main"
    const val SUB_SETTING = "sub_setting"
    const val PER_APP_PROXY = "per_app_proxy"
    const val ROUTING_SETTING = "routing_setting"
    const val USER_ASSET = "user_asset"
    const val SETTINGS = "settings"
    const val LOGCAT = "logcat"
    const val CHECK_UPDATE = "check_update"
    const val BACKUP = "backup"
    const val ABOUT = "about"
    const val SCANNER = "scanner"

    const val SERVER_EDIT = "server_edit"
    const val ROUTING_EDIT = "routing_edit"
    const val SUB_EDIT = "sub_edit"
    const val USER_ASSET_URL = "user_asset_url"
    const val APP_PICKER = "app_picker"
}

private fun AppRoute.toNavRoute(): String = when (this) {
    AppRoute.Main -> NavRoute.MAIN
    AppRoute.SubSetting -> NavRoute.SUB_SETTING
    AppRoute.PerAppProxy -> NavRoute.PER_APP_PROXY
    AppRoute.RoutingSetting -> NavRoute.ROUTING_SETTING
    AppRoute.UserAsset -> NavRoute.USER_ASSET
    AppRoute.Settings -> NavRoute.SETTINGS
    AppRoute.Logcat -> NavRoute.LOGCAT
    AppRoute.CheckUpdate -> NavRoute.CHECK_UPDATE
    AppRoute.Backup -> NavRoute.BACKUP
    AppRoute.About -> NavRoute.ABOUT
    AppRoute.Scanner -> NavRoute.SCANNER
    is AppRoute.ServerEdit ->
        "${NavRoute.SERVER_EDIT}?${AppRoute.EXTRA_TYPE}=${configType.value}" +
            "&${AppRoute.EXTRA_GUID}=${Uri.encode(guid)}" +
            "&${AppRoute.EXTRA_SUB_ID}=${Uri.encode(subscriptionId)}" +
            "&${AppRoute.EXTRA_RUNNING}=$isRunning"
    is AppRoute.RoutingEdit ->
        "${NavRoute.ROUTING_EDIT}?${AppRoute.EXTRA_RULE_ID}=${Uri.encode(ruleId)}"
    is AppRoute.SubEdit ->
        "${NavRoute.SUB_EDIT}?${AppRoute.EXTRA_SUB_GUID}=${Uri.encode(subId)}"
    is AppRoute.UserAssetUrl ->
        "${NavRoute.USER_ASSET_URL}?${AppRoute.EXTRA_ASSET_ID}=${Uri.encode(assetId)}" +
            "&${AppRoute.EXTRA_ASSET_QRCODE}=${Uri.encode(qrCodeUrl)}"
    is AppRoute.AppPicker ->
        "${NavRoute.APP_PICKER}?${AppRoute.EXTRA_PICKER_SELECTED}=" +
            Uri.encode(selected.joinToString(SELECTED_SEPARATOR)) +
            "&${AppRoute.EXTRA_PICKER_TITLE_RES}=$titleRes"
    is AppRoute.OpenUrl -> error("OpenUrl is handled before route conversion")
}

private fun enterFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(TransitionDurationMillis, easing = FastOutSlowInEasing),
        initialOffsetX = { (it * TransitionOffsetFraction).roundToInt() }
    ) + fadeIn(tween(TransitionDurationMillis, easing = FastOutSlowInEasing))

private fun exitToLeft(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(TransitionDurationMillis, easing = FastOutLinearInEasing),
        targetOffsetX = { (-it * TransitionOffsetFraction).roundToInt() }
    ) + fadeOut(tween(TransitionDurationMillis, easing = FastOutLinearInEasing))

private fun enterFromLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(TransitionDurationMillis, easing = FastOutSlowInEasing),
        initialOffsetX = { (-it * TransitionOffsetFraction).roundToInt() }
    ) + fadeIn(tween(TransitionDurationMillis, easing = FastOutSlowInEasing))

private fun exitToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(TransitionDurationMillis, easing = FastOutLinearInEasing),
        targetOffsetX = { (it * TransitionOffsetFraction).roundToInt() }
    ) + fadeOut(tween(TransitionDurationMillis, easing = FastOutLinearInEasing))

private class NavEntryNavigator(
    private val entry: NavBackStackEntry,
    private val navigateRoute: (AppRoute) -> Boolean,
    private val popRoute: (BaseResult) -> Boolean,
) : AppNavigator {
    override val results: Flow<BaseResult?> = entry.savedStateHandle
        .getStateFlow<Intent?>(RESULT_KEY, null)
        .map { it?.toBaseResult() }

    override fun navigate(route: BaseRoute): Boolean =
        (route as? AppRoute)?.let(navigateRoute) == true

    override fun finish(result: BaseResult): Boolean = popRoute(result)

    override fun consumeResult() {
        entry.savedStateHandle[RESULT_KEY] = null
    }
}

private fun NavBackStackEntry.setPickerArgumentsFromRoute() {
    val selected = arguments?.getString(AppRoute.EXTRA_PICKER_SELECTED)
        .orEmpty()
        .split(SELECTED_SEPARATOR)
        .filter(String::isNotEmpty)
    savedStateHandle[AppRoute.EXTRA_PICKER_SELECTED] = ArrayList(selected)
}

private const val SERVER_EDIT_ROUTE =
    "${NavRoute.SERVER_EDIT}?${AppRoute.EXTRA_TYPE}={${AppRoute.EXTRA_TYPE}}" +
        "&${AppRoute.EXTRA_GUID}={${AppRoute.EXTRA_GUID}}" +
        "&${AppRoute.EXTRA_SUB_ID}={${AppRoute.EXTRA_SUB_ID}}" +
        "&${AppRoute.EXTRA_RUNNING}={${AppRoute.EXTRA_RUNNING}}"

private const val ROUTING_EDIT_ROUTE =
    "${NavRoute.ROUTING_EDIT}?${AppRoute.EXTRA_RULE_ID}={${AppRoute.EXTRA_RULE_ID}}"

private const val SUB_EDIT_ROUTE =
    "${NavRoute.SUB_EDIT}?${AppRoute.EXTRA_SUB_GUID}={${AppRoute.EXTRA_SUB_GUID}}"

private const val USER_ASSET_URL_ROUTE =
    "${NavRoute.USER_ASSET_URL}?${AppRoute.EXTRA_ASSET_ID}={${AppRoute.EXTRA_ASSET_ID}}" +
        "&${AppRoute.EXTRA_ASSET_QRCODE}={${AppRoute.EXTRA_ASSET_QRCODE}}"

private const val APP_PICKER_ROUTE =
    "${NavRoute.APP_PICKER}?${AppRoute.EXTRA_PICKER_SELECTED}={${AppRoute.EXTRA_PICKER_SELECTED}}" +
        "&${AppRoute.EXTRA_PICKER_TITLE_RES}={${AppRoute.EXTRA_PICKER_TITLE_RES}}"

/** The single in-app Compose graph; Activity wrappers remain for external and compatibility entry points. */
@Composable
fun AppNavHost(
    mainViewModel: MainViewModel,
    onMainPlatformEvent: (MainEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val platform = LocalPlatformActions.current
    val navController = rememberNavController()

    fun navigate(route: AppRoute): Boolean {
        if (route is AppRoute.OpenUrl) return false
        if (route is AppRoute.Main) {
            navController.popBackStack(NavRoute.MAIN, inclusive = false)
            return true
        }
        navController.navigate(route.toNavRoute()) {
            launchSingleTop = route is AppRoute.SubSetting ||
                route is AppRoute.PerAppProxy ||
                route is AppRoute.RoutingSetting ||
                route is AppRoute.UserAsset ||
                route is AppRoute.Settings ||
                route is AppRoute.Logcat ||
                route is AppRoute.CheckUpdate ||
                route is AppRoute.Backup ||
                route is AppRoute.About ||
                route is AppRoute.Scanner
        }
        return true
    }

    fun finish(entry: NavBackStackEntry, result: BaseResult): Boolean {
        val previous = navController.previousBackStackEntry ?: return false
        previous.savedStateHandle[RESULT_KEY] = result.toIntent()
        return navController.popBackStack()
    }

    @Composable
    fun navigator(entry: NavBackStackEntry): AppNavigator = remember(entry) {
        NavEntryNavigator(
            entry = entry,
            navigateRoute = ::navigate,
            popRoute = { result -> finish(entry, result) },
        )
    }

    NavHost(
        navController = navController,
        startDestination = NavRoute.MAIN,
        modifier = modifier.fillMaxSize(),
        enterTransition = { enterFromRight() },
        exitTransition = { exitToLeft() },
        popEnterTransition = { enterFromLeft() },
        popExitTransition = { exitToRight() },
        predictivePopEnterTransition = { _ -> enterFromLeft() },
        predictivePopExitTransition = { _ -> exitToRight() },
    ) {
        composable(NavRoute.MAIN) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                MainScreen(mainViewModel, onMainPlatformEvent)
            }
        }

        composable(NavRoute.SUB_SETTING) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                SubSettingScreen(hiltViewModel<SubSettingViewModel>())
            }
        }

        composable(NavRoute.PER_APP_PROXY) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                PerAppProxyScreen(hiltViewModel<PerAppProxyViewModel>())
            }
        }

        composable(NavRoute.ROUTING_SETTING) { entry ->
            val viewModel = hiltViewModel<RoutingSettingViewModel>()
            val platformEventHandler: (RoutingEvent) -> Boolean = remember(viewModel, platform) {
                { event ->
                    when (event) {
                        RoutingEvent.ScanQrCode -> {
                            platform.scanQrCode { text ->
                                viewModel.onAction(RoutingAction.QrCodeScanned(text))
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                RoutingSettingScreen(viewModel, platformEventHandler)
            }
        }

        composable(NavRoute.USER_ASSET) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                UserAssetScreen(hiltViewModel<UserAssetViewModel>())
            }
        }

        composable(NavRoute.SETTINGS) { entry ->
            val viewModel = hiltViewModel<SettingsViewModel>()
            val platformEventHandler: (SettingsEvent) -> Boolean = remember(viewModel, context) {
                { event ->
                    when (event) {
                        is SettingsEvent.ApplyLanguage -> {
                            AppLocaleManager.setApplicationLanguage(context, event.code)
                            true
                        }
                        SettingsEvent.PredictiveBackChanged -> {
                            context.findActivity()?.recreate()
                            true
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                SettingsScreen(viewModel, platformEventHandler)
            }
        }

        composable(NavRoute.LOGCAT) { entry ->
            val viewModel = hiltViewModel<LogcatViewModel>()
            val platformEventHandler: (LogcatEvent) -> Boolean = remember(viewModel, context) {
                { event ->
                    when (event) {
                        is LogcatEvent.ShareFile -> {
                            val ok = ShareUtil.shareFile(
                                context = context,
                                file = File(event.path),
                                mimeType = "text/plain",
                                title = context.getString(R.string.logcat_share)
                            )
                            viewModel.onAction(LogcatAction.ShareFinished(ok))
                            true
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                LogcatScreen(viewModel, platformEventHandler)
            }
        }

        composable(NavRoute.CHECK_UPDATE) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                CheckUpdateScreen(hiltViewModel<CheckUpdateViewModel>())
            }
        }

        composable(NavRoute.BACKUP) { entry ->
            val viewModel = hiltViewModel<BackupViewModel>()
            val platformEventHandler: (BackupEvent) -> Boolean = remember(viewModel, context, platform) {
                { event ->
                    when (event) {
                        is BackupEvent.CreateDocument -> {
                            platform.createDocument(event.fileName) { uri ->
                                viewModel.onAction(BackupAction.ExportUriSelected(uri))
                            }
                            true
                        }
                        BackupEvent.PickFile -> {
                            platform.pickFile { uri ->
                                viewModel.onAction(BackupAction.ImportUriSelected(uri))
                            }
                            true
                        }
                        is BackupEvent.ShareFile -> {
                            val started = ShareUtil.shareFile(
                                context = context,
                                file = File(event.path),
                                mimeType = "application/zip",
                                title = context.getString(R.string.title_configuration_share)
                            )
                            viewModel.onAction(BackupAction.ShareResult(event.path, started))
                            true
                        }
                        else -> false
                    }
                }
            }
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                BackupScreen(viewModel, platformEventHandler)
            }
        }

        composable(NavRoute.ABOUT) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                AboutScreen(hiltViewModel<AboutViewModel>())
            }
        }

        composable(NavRoute.SCANNER) { entry ->
            val viewModel = hiltViewModel<ScannerViewModel>()
            val platformEventHandler: (ScannerEvent) -> Boolean = remember(viewModel, platform) {
                { event ->
                    when (event) {
                        ScannerEvent.RequestCameraPermission -> {
                            platform.requestPermission(PermissionType.CAMERA) {
                                viewModel.onAction(ScannerAction.CameraPermissionGranted)
                            }
                            true
                        }
                        ScannerEvent.PickImage -> {
                            platform.pickFile("image/*") { uri ->
                                viewModel.onAction(ScannerAction.ImageSelected(uri))
                            }
                            true
                        }
                    }
                }
            }
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                ScannerScreen(viewModel, platformEventHandler)
            }
        }

        composable(
            route = SERVER_EDIT_ROUTE,
            arguments = listOf(
                navArgument(AppRoute.EXTRA_TYPE) { type = NavType.IntType },
                navArgument(AppRoute.EXTRA_GUID) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoute.EXTRA_SUB_ID) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoute.EXTRA_RUNNING) { type = NavType.BoolType; defaultValue = false },
            )
        ) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                ServerEditScreen(hiltViewModel<ServerEditViewModel>())
            }
        }

        composable(
            route = ROUTING_EDIT_ROUTE,
            arguments = listOf(
                navArgument(AppRoute.EXTRA_RULE_ID) { type = NavType.StringType; defaultValue = "" },
            )
        ) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                RoutingEditScreen(hiltViewModel<RoutingEditViewModel>())
            }
        }

        composable(
            route = SUB_EDIT_ROUTE,
            arguments = listOf(
                navArgument(AppRoute.EXTRA_SUB_GUID) { type = NavType.StringType; defaultValue = "" },
            )
        ) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                SubEditScreen(hiltViewModel<SubEditViewModel>())
            }
        }

        composable(
            route = USER_ASSET_URL_ROUTE,
            arguments = listOf(
                navArgument(AppRoute.EXTRA_ASSET_ID) { type = NavType.StringType; defaultValue = "" },
                navArgument(AppRoute.EXTRA_ASSET_QRCODE) { type = NavType.StringType; defaultValue = "" },
            )
        ) { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                UserAssetUrlScreen(hiltViewModel<UserAssetUrlViewModel>())
            }
        }

        composable(
            route = APP_PICKER_ROUTE,
            arguments = listOf(
                navArgument(AppRoute.EXTRA_PICKER_SELECTED) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(AppRoute.EXTRA_PICKER_TITLE_RES) {
                    type = NavType.IntType
                    defaultValue = 0
                },
            )
        ) { entry ->
            entry.setPickerArgumentsFromRoute()
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                AppPickerScreen(hiltViewModel<AppPickerViewModel>())
            }
        }
    }
}
