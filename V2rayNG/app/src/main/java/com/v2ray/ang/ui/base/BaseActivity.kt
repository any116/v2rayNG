package com.v2ray.ang.ui.base

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.PredictiveBackManager
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.LocalPredictiveBackProgress
import com.v2ray.ang.ui.components.PredictiveBackLayout
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

abstract class BaseActivity : ComponentActivity() {

    private var predictiveBackEnabled = true
    private var legacyBackCallback: Any? = null

    override fun attachBaseContext(newBase: Context?) {
        val base = requireNotNull(newBase) { "attachBaseContext called with null" }
        super.attachBaseContext(AppLocaleManager.localizedContext(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        predictiveBackEnabled = PredictiveBackManager.isEnabled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !predictiveBackEnabled) {
            // This callback is only the explicit opt-out bridge. It is never registered on the
            // predictive path, so it cannot compete with PredictiveBackHandler.
            legacyBackCallback = Api33Impl.register(this)
        }
        setContent {
            CompositionLocalProvider(
                LocalPlatformActions provides (this@BaseActivity as? PlatformActions
                    ?: NoPlatformActions)
            ) {
                PredictiveBackLayout(enabled = predictiveBackEnabled) {
                    LocalizedContent()
                }
            }
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            legacyBackCallback?.let { Api33Impl.unregister(this, it) }
        }
        legacyBackCallback = null
        super.onDestroy()
    }

    @Composable
    protected abstract fun ScreenContent()

    /**
     * Keeps the first frame synchronous while applying a later locale refresh outside an active
     * predictive-back gesture. The wrapper equality check makes the normal ready-at-start path a
     * no-op, and the gesture gate prevents a cold-start locale refresh from disposing back
     * handlers while Android is animating the current Activity away.
     */
    @Composable
    private fun LocalizedContent() {
        var localeContext by remember {
            mutableStateOf(AppLocaleManager.localizedContext(this@BaseActivity))
        }
        val predictiveBackState = LocalPredictiveBackProgress.current
        val currentPredictiveBackState by rememberUpdatedState(predictiveBackState)

        LaunchedEffect(Unit) {
            AppLocaleManager.prepareActivity(this@BaseActivity)
            val refreshed = AppLocaleManager.localizedContext(this@BaseActivity)
            if (refreshed != localeContext) {
                val completed = withTimeoutOrNull(LOCALE_REFRESH_BACK_TIMEOUT_MS) {
                    snapshotFlow { currentPredictiveBackState?.isInProgress == true }
                        .first { !it }
                }
                if (completed == null) {
                    LogUtil.e(AppConfig.TAG, "Locale refresh skipped: predictive-back timeout")
                    return@LaunchedEffect
                }
                if (refreshed != localeContext) localeContext = refreshed
            }
        }

        CompositionLocalProvider(
            LocalContext provides localeContext,
            LocalResources provides localeContext.resources,
        ) {
            AppTheme { ScreenContent() }
        }
    }

    private companion object {
        const val LOCALE_REFRESH_BACK_TIMEOUT_MS = 2_000L
    }

    /** Isolates API 33 references so older Android versions do not verify them at class load time. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33Impl {
        fun register(activity: BaseActivity): Any {
            val callback = android.window.OnBackInvokedCallback {
                activity.onBackPressedDispatcher.onBackPressed()
            }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback
            )
            return callback
        }

        fun unregister(activity: BaseActivity, callback: Any) {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(
                callback as android.window.OnBackInvokedCallback
            )
        }
    }

}
