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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.PredictiveBackManager
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.PredictiveBackLayout

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
            var localeContext by remember {
                mutableStateOf(AppLocaleManager.localizedContext(this@BaseActivity))
            }
            LaunchedEffect(Unit) {
                AppLocaleManager.prepareActivity(this@BaseActivity)
                // The first frame is available immediately; refresh the CompositionLocals once
                // the Room-backed locale snapshot/framework migration is ready.
                localeContext = AppLocaleManager.localizedContext(this@BaseActivity)
            }
            CompositionLocalProvider(
                LocalContext provides localeContext,
                LocalResources provides localeContext.resources,
                LocalPlatformActions provides (this@BaseActivity as? PlatformActions
                    ?: NoPlatformActions)
            ) {
                AppTheme {
                    PredictiveBackLayout(enabled = predictiveBackEnabled) {
                        ScreenContent()
                    }
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
