package com.v2ray.ang.ui.base

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.PredictiveBackManager
import com.v2ray.ang.ui.components.AppTheme
import com.v2ray.ang.ui.components.PredictiveBackLayout

abstract class BaseActivity : AppCompatActivity() {

    private var predictiveBackEnabled = true
    private var legacyBackCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLocaleManager.onActivityCreated(this)
        enableEdgeToEdge()
        predictiveBackEnabled = PredictiveBackManager.isEnabled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !predictiveBackEnabled) {
            legacyBackCallback = Api33Impl.register(this)
        }
        setContent {
            AppTheme {
                CompositionLocalProvider(
                    LocalPlatformActions provides (this as? PlatformActions ?: NoPlatformActions)
                ) {
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
