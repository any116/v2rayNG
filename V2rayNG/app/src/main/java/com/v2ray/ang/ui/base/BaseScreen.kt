package com.v2ray.ang.ui.base

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.ExternalEntry
import com.v2ray.ang.ui.components.LocalAppSnackbar
import com.v2ray.ang.ui.components.ToastType
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.flow.Flow

/** Walks ContextWrapper chain to find the hosting Activity. */
fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()

/**
 * Universal screen wrapper.
 *
 * Provides [Scaffold] with slots, collects [uiState], and handles navigation/events.
 *
 * @param onEvent Optional interceptor for events; return `true` to consume.
 * @param onResult Callback for [BaseResult] from child screens.
 */
@Composable
fun <S : BaseUiState, A : BaseAction> BaseScreen(
    viewModel: BaseViewModel<S, A>,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = WindowInsets(0),
    autoToastResult: Boolean = true,
    onEvent: (BaseEvent) -> Boolean = { false },
    onResult: (BaseResult) -> Unit = {},
    content: @Composable (state: S, onAction: (A) -> Unit) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val okText = stringResource(R.string.toast_success)
    val navigator = LocalAppNavigator.current

    val resultHandler by rememberUpdatedState(onResult)
    val lifecycleOwner = LocalLifecycleOwner.current

    if (navigator != null) {
        LaunchedEffect(navigator, lifecycleOwner) {
            // A child may return while the parent is still entering; deliver only after its pop.
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                navigator.results.collect { result ->
                    result ?: return@collect
                    navigator.consumeResult()
                    if (autoToastResult && result.notify) snackbar.show(okText, ToastType.SUCCESS)
                    resultHandler(result)
                }
            }
        }
    }

    BaseEventEffect(
        events = viewModel.events,
        onFinish = { result ->
            if (navigator == null) false else {
                if (!navigator.finish(result)) {
                    LogUtil.e(AppConfig.TAG, "The navigation host did not handle finish; ignoring request")
                }
                // A failed graph pop must not fall through to finishing the host Activity.
                true
            }
        },
        onEvent = { event ->
            when {
                onEvent(event) -> true
                event is BaseEvent.Navigate -> when (val route = event.route) {
                    is AppRoute.OpenUrl -> {
                        Utils.openUri(context, route.url)
                        true
                    }
                    else -> {
                        if (navigator != null) {
                            if (!navigator.navigate(route)) {
                                LogUtil.e(AppConfig.TAG, "In-app navigation not handled: $route")
                            }
                        } else {
                            // Only an external host can transfer control to the root Activity.
                            val entry = ExternalEntry.fromRoute(route)
                            if (entry == null) {
                                LogUtil.e(AppConfig.TAG, "No external entry for $route; ignoring request")
                            } else {
                                context.startActivity(entry.toIntent(context))
                            }
                        }
                        // Consumed even when ignored: navigation has no further fallback handler.
                        true
                    }
                }
                else -> false
            }
        },
    )

    val dispatch = remember(viewModel) { viewModel::onAction }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = topBar,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = contentWindowInsets
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            content(state, dispatch)
        }
    }
}

/**
 * Lifecycle-aware consumer of one-time [BaseEvent]s.
 *
 * Events are collected while the lifecycle is at least STARTED; buffered events are delivered on
 * resume.
 */
@Composable
fun BaseEventEffect(
    events: Flow<BaseEvent>,
    onEvent: (BaseEvent) -> Boolean = { false },
    onFinish: (BaseResult) -> Boolean = { false },
) {
    val context = LocalContext.current
    val snackbar = LocalAppSnackbar.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val handler by rememberUpdatedState(onEvent)
    val finishHandler by rememberUpdatedState(onFinish)

    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { event ->
                if (handler(event)) return@collect
                when (event) {
                    is BaseEvent.Message -> snackbar.show(
                        message = event.message.text.asString(context),
                        type = event.message.type,
                        long = event.message.long,
                    )
                    is BaseEvent.Finish -> if (!finishHandler(event.result)) {
                        context.findActivity()?.finishWithResult(event.result)
                    }
                    // Navigate is intercepted by BaseScreen; Platform is handled by host Activity.
                    is BaseEvent.Navigate, is BaseEvent.Platform -> Unit
                }
            }
        }
    }
}
