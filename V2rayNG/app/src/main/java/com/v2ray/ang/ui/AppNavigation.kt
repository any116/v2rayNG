package com.v2ray.ang.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContentTransitionScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.v2ray.ang.AppConfig
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
import com.v2ray.ang.ui.components.LocalPredictiveBackProgress
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
import com.v2ray.ang.ui.routing.RoutingEditScreen
import com.v2ray.ang.ui.routing.RoutingEditViewModel
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
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ShareUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.roundToInt

private const val RESULT_KEY = "app_navigation_result"
private const val TransitionDurationMillis = 400
private const val TransitionOffsetFraction = 0.15f

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

/**
 * Keeps the retained home screen stationary while the smaller child destination leaves.
 *
 * Main contains a paged server list, so animating its whole content on every frame doubles the
 * amount of work during a pop. The child still slides away, which preserves the navigation cue
 * without competing with the home screen's first visible frame.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnterTransitionForDestination(): EnterTransition =
    if (targetState.destination.hasRoute<AppRoute.Main>()) EnterTransition.None else enterFromLeft()

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

/** The only application screen graph; even bootstrap recovery and shortcut scanning use it. */
@Composable
fun AppNavHost(
    mainViewModel: MainViewModel,
    onMainPlatformEvent: (MainEvent) -> Boolean,
    onRootFinish: (BaseResult) -> Unit,
    modifier: Modifier = Modifier,
    startDestination: AppRoute = AppRoute.Main,
    externalEntryAction: String? = null,
    onExternalEntryHandled: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val platform = LocalPlatformActions.current
    val navController = rememberNavController()
    val rootFinish by rememberUpdatedState(onRootFinish)
    val entryHandled by rememberUpdatedState(onExternalEntryHandled)
    val backProgress = LocalPredictiveBackProgress.current

    /**
     * The route type decides the destination, so each branch is a plain `navigate(route)` call.
     * Arguments are read from the route object by Navigation; nothing is encoded here.
     */
    fun navigate(route: AppRoute): Boolean {
        when (route) {
            // Not an Activity navigation; the host opens the link instead.
            is AppRoute.OpenUrl -> return false
            // MAIN is the graph root: this is "return home", not "push another home".
            is AppRoute.Main -> navController.popBackStack<AppRoute.Main>(inclusive = false)
            is AppRoute.SubSetting -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.PerAppProxy -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.RoutingSetting -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.UserAsset -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.Settings -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.Logcat -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.CheckUpdate -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.Backup -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.About -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.Scanner -> navController.navigate(route) { launchSingleTop = true }
            is AppRoute.ServerEdit -> navController.navigate(route)
            is AppRoute.RoutingEdit -> navController.navigate(route)
            is AppRoute.SubEdit -> navController.navigate(route)
            is AppRoute.UserAssetUrl -> navController.navigate(route)
            is AppRoute.AppPicker -> navController.navigate(route)
        }
        return true
    }

    fun finish(entry: NavBackStackEntry, result: BaseResult): Boolean {
        // An asynchronous finish from an exiting entry must never pop the new destination.
        if (entry != navController.currentBackStackEntry) {
            LogUtil.d(AppConfig.TAG, "Ignoring finish from inactive navigation entry ${entry.id}")
            return true
        }
        val previous = navController.previousBackStackEntry
        if (previous == null) {
            // Recovery exits locally; no Activity pause/resume will reset gesture suppression.
            if (!entry.destination.hasRoute<AppRoute.Main>()) backProgress?.onBackFinished()
            rootFinish(result)
            return true
        }
        val resultHandle = previous.savedStateHandle
        val previousResult = resultHandle.get<Intent>(RESULT_KEY)
        resultHandle[RESULT_KEY] = result.toIntent()
        val popped = navController.popBackStack()
        if (!popped) {
            // Restore the old value without remove(), which would detach the existing StateFlow.
            resultHandle[RESULT_KEY] = previousResult
            backProgress?.onBackFinished()
        }
        return popped
    }

    @Composable
    fun navigator(entry: NavBackStackEntry): AppNavigator = remember(entry) {
        NavEntryNavigator(
            entry = entry,
            navigateRoute = { route ->
                if (entry != navController.currentBackStackEntry) {
                    LogUtil.d(AppConfig.TAG, "Ignoring navigation from inactive entry ${entry.id}: $route")
                    // Consumed deliberately: a late event must not navigate from the replacement entry.
                    true
                } else {
                    navigate(route)
                }
            },
            popRoute = { result -> finish(entry, result) },
        )
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize(),
        enterTransition = { enterFromRight() },
        exitTransition = { exitToLeft() },
        popEnterTransition = { popEnterTransitionForDestination() },
        popExitTransition = { exitToRight() },
        predictivePopEnterTransition = { _ -> popEnterTransitionForDestination() },
        predictivePopExitTransition = { _ -> exitToRight() },
    ) {
        composable<AppRoute.Main> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                MainScreen(mainViewModel, onMainPlatformEvent)
            }
        }

        composable<AppRoute.SubSetting> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                SubSettingScreen(hiltViewModel<SubSettingViewModel>())
            }
        }

        composable<AppRoute.PerAppProxy> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                PerAppProxyScreen(hiltViewModel<PerAppProxyViewModel>())
            }
        }

        composable<AppRoute.RoutingSetting> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                RoutingSettingScreen(hiltViewModel<RoutingSettingViewModel>())
            }
        }

        composable<AppRoute.UserAsset> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                UserAssetScreen(hiltViewModel<UserAssetViewModel>())
            }
        }

        composable<AppRoute.Settings> { entry ->
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

        composable<AppRoute.Logcat> { entry ->
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

        composable<AppRoute.CheckUpdate> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                CheckUpdateScreen(hiltViewModel<CheckUpdateViewModel>())
            }
        }

        composable<AppRoute.Backup> { entry ->
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

        composable<AppRoute.About> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                AboutScreen(hiltViewModel<AboutViewModel>())
            }
        }

        composable<AppRoute.Scanner> { entry ->
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

        composable<AppRoute.ServerEdit> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                ServerEditScreen(hiltViewModel<ServerEditViewModel>())
            }
        }

        composable<AppRoute.RoutingEdit> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                RoutingEditScreen(hiltViewModel<RoutingEditViewModel>())
            }
        }

        composable<AppRoute.SubEdit> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                SubEditScreen(hiltViewModel<SubEditViewModel>())
            }
        }

        composable<AppRoute.UserAssetUrl> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                UserAssetUrlScreen(hiltViewModel<UserAssetUrlViewModel>())
            }
        }

        composable<AppRoute.AppPicker> { entry ->
            CompositionLocalProvider(LocalAppNavigator provides navigator(entry)) {
                AppPickerScreen(hiltViewModel<AppPickerViewModel>())
            }
        }
    }

    // Run after NavHost installs the graph. Restored requests are cleared once, not on every resume.
    LaunchedEffect(externalEntryAction) {
        val action = externalEntryAction ?: return@LaunchedEffect
        // Claim the request before changing the stack so subsequent saved state cannot replay it.
        entryHandled()
        navController.popBackStack<AppRoute.Main>(inclusive = false)
        if (action == ExternalEntry.ACTION_SCAN) navigate(AppRoute.Scanner)
    }
}
