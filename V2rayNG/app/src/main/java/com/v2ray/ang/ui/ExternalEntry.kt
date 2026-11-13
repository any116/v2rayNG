package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import com.v2ray.ang.ui.base.BaseRoute
import com.v2ray.ang.ui.main.MainActivity

/** Explicit Android entry points. Ordinary destinations never construct or launch an Activity. */
sealed interface ExternalEntry {
    fun toIntent(context: Context): Intent

    data object Main : ExternalEntry {
        override fun toIntent(context: Context): Intent = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_MAIN)
    }

    /** The static shortcut keeps its component name but delegates scanning to Main's Compose graph. */
    data object Scanner : ExternalEntry {
        override fun toIntent(context: Context): Intent =
            Main.toIntent(context).setAction(ACTION_SCAN)
    }

    companion object {
        const val ACTION_OPEN_MAIN = "com.v2ray.ang.action.OPEN_MAIN"
        const val ACTION_SCAN = "com.v2ray.ang.action.SCAN_QR_CODE"

        /**
         * Only external hosts may hand control back to Main; this is not a graph fallback.
         * Returns null for ordinary destinations so callers can report and ignore unsupported requests.
         */
        fun fromRoute(route: BaseRoute): ExternalEntry? = if (route == AppRoute.Main) Main else null
    }
}
