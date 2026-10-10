package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.Prefs

/** Resolves the user opt-out for Android's predictive-back dispatcher. */
object PredictiveBackManager {

    fun isEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return Prefs.bool(AppConfig.PREF_PREDICTIVE_BACK, true)
    }
}
