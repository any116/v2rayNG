package com.v2ray.ang.repository

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.AppInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/**
 * Data layer for screens that list installed applications (app picker, per-app proxy).
 */
open class AppListRepository @Inject constructor(
    private val app: Application,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    // ---------- Loading ----------

    open suspend fun loadApps(
        selectedSnapshot: Set<String> = emptySet(),
        includeUnidentified: Boolean = true
    ): List<AppInfo> = withIO {
        val sorted = sortApps(queryAllApps(), selectedSnapshot)
        if (!includeUnidentified) {
            sorted
        } else {
            buildList(sorted.size + 1) {
                add(unidentifiedApp())
                addAll(sorted)
            }
        }
    }

    private suspend fun queryAllApps(): List<AppInfo> {
        val packageManager = app.packageManager
        val packages = installedPackages(packageManager)
        val apps = ArrayList<AppInfo>(packages.size)
        for (pkg in packages) {
            currentCoroutineContext().ensureActive()
            val applicationInfo = pkg.applicationInfo ?: continue
            apps.add(
                AppInfo(
                    appName = applicationInfo.loadLabel(packageManager).toString(),
                    packageName = pkg.packageName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM > 0
                )
            )
        }
        return apps
    }

    private fun installedPackages(packageManager: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(0L)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0)
        }

    private fun unidentifiedApp() = AppInfo(
        appName = "",
        packageName = AppConfig.UNIDENTIFIED_PACKAGE,
        isSystemApp = false
    )

    private fun sortApps(apps: List<AppInfo>, selected: Set<String>): List<AppInfo> {
        val collator = Collator.getInstance(Locale.getDefault())
        return apps.sortedWith { a, b ->
            val aSelected = a.packageName in selected
            val bSelected = b.packageName in selected
            when {
                aSelected != bSelected -> if (aSelected) -1 else 1
                a.isSystemApp != b.isSystemApp -> if (a.isSystemApp) 1 else -1
                else -> collator.compare(a.appName, b.appName)
            }
        }
    }

    // ---------- Filtering ----------
    open fun filter(apps: List<AppInfo>, query: String): List<AppInfo> {
        if (query.isBlank()) return apps
        return apps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    // ---------- Selection arithmetic (pure, no I/O) ----------
    open fun selectAll(current: Set<String>, packageNames: Collection<String>): Set<String> =
        buildSet(current.size + packageNames.size) {
            addAll(current)
            addAll(packageNames)
        }

    open fun invert(current: Set<String>, packageNames: Collection<String>): Set<String> =
        current.toMutableSet().apply {
            packageNames.forEach { if (!add(it)) remove(it) }
        }

    open fun fromProxyList(
        packageNames: Collection<String>,
        proxyAppList: String,
        bypassApps: Boolean,
        forceGoogleApps: Boolean
    ): Set<String> = buildSet(packageNames.size) {
        packageNames.forEach { packageName ->
            val proxied = shouldProxy(packageName, proxyAppList, forceGoogleApps)
            if (if (bypassApps) !proxied else proxied) add(packageName)
        }
    }

    private fun shouldProxy(
        packageName: String,
        proxyAppList: String,
        forceGoogleApps: Boolean
    ): Boolean {
        if (forceGoogleApps) {
            if (packageName == GOOGLE_WEBVIEW_PACKAGE) return false
            if (packageName.startsWith(GOOGLE_PACKAGE_PREFIX)) return true
        }
        return proxyAppList.contains(packageName)
    }

    private companion object {
        const val GOOGLE_PACKAGE_PREFIX = "com.google"
        const val GOOGLE_WEBVIEW_PACKAGE = "com.google.android.webview"
    }
}
