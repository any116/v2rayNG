package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.toast
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.ui.AppRoute
import com.v2ray.ang.ui.base.BaseHelperActivity
import com.v2ray.ang.util.LogUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@AndroidEntryPoint
class MainActivity : BaseHelperActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var pendingLocalNetwork = false

    /** The permission request moved behind the storage barrier; ask at most once per Activity. */
    private var notificationAsked = false

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) launchCore(pendingLocalNetwork)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The notification permission request moved behind the storage barrier (see
        // ScreenContent): a system dialog on the very first frame competes with first draw.
        viewModel.onAction(MainAction.Initialize)
    }

    @Composable
    override fun ScreenContent() {
        // An attempt that does not end in "ready" shows an explicit failure/retry surface
        // instead of the main UI. Retry really re-runs the storage bootstrap now.
        var attempt by remember { mutableIntStateOf(0) }
        // Pure, side-effect-free check: a recreated Activity in a live process starts out ready
        // and never draws the spinner frame. State seeding and observer startup still happen
        // exactly once, inside awaitReady() below — never during composition.
        var ready by remember { mutableStateOf(viewModel.isStorageReadyNow()) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(attempt) {
            failed = false
            // No-op while an attempt is still running (timeout case) or after success.
            if (attempt > 0) {
                ready = false
                viewModel.retryStorage()
            }
            // Always awaited, even on the fast path: awaitReady() returns immediately when the
            // barrier is already open, and it is the single seeding entry point.
            val cause: Throwable? = try {
                val done = withTimeoutOrNull(BOOT_TIMEOUT_MS) { viewModel.awaitReady() }
                if (done == null) TimeoutException() else null
            } catch (e: CancellationException) {
                throw e // leaving composition is not a storage failure
            } catch (e: Exception) {
                e
            }
            if (cause == null) {
                ready = true
            } else {
                ready = false
                failed = true
                LogUtil.w(AppConfig.TAG, "Storage initialization failed; showing retry surface", cause)
            }
        }
        when {
            ready -> {
                LaunchedEffect(Unit) {
                    if (!notificationAsked) {
                        notificationAsked = true
                        requestPermission(PermissionType.POST_NOTIFICATIONS) {}
                    }
                }
                MainScreen(
                    viewModel = viewModel,
                    onPlatformEvent = ::handlePlatformEvent
                )
            }

            failed -> BootFailureContent(
                onRetry = { attempt++ },
                onSkipLegacyImport = {
                    lifecycleScope.launch {
                        // Marking the import done is what makes the retry able to succeed; if the
                        // marker cannot be written (a genuinely unusable database) the user needs
                        // the restore path instead, so stay on this screen.
                        if (viewModel.abandonLegacyImport()) attempt++ else toast(R.string.boot_storage_failed_message)
                    }
                },
                onRestoreFromWebdav = { startActivity(AppRoute.Backup.intent(this)) }
            )

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    /** Distinguishes "the bootstrap did not settle in time" from an actual failure. */
    private class TimeoutException : Exception("Storage bootstrap timed out")

    @Composable
    private fun BootFailureContent(
        onRetry: () -> Unit,
        onSkipLegacyImport: () -> Unit,
        onRestoreFromWebdav: () -> Unit
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.boot_storage_failed_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(text = stringResource(R.string.boot_storage_failed_message))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.action_retry))
                }
                // Escape hatches. Some failures are permanent — an unreadable MMKV store or a
                // staged snapshot that never deserializes — and retrying those locks the user
                // out of the app forever, so there must be an explicit way out.
                Spacer(Modifier.height(24.dp))
                Text(text = stringResource(R.string.boot_storage_failed_escape))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSkipLegacyImport) {
                    Text(text = stringResource(R.string.action_skip_legacy_import))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRestoreFromWebdav) {
                    Text(text = stringResource(R.string.action_restore_from_webdav))
                }
            }
        }
    }

    private fun handlePlatformEvent(event: MainEvent): Boolean = when (event) {
        is MainEvent.StartService -> {
            startCore(event.requireVpnPermission, event.requireLocalNetwork); true
        }
        MainEvent.StopService -> {
            LauncherManager.stopService(this); true
        }
        is MainEvent.RestartService -> {
            if (event.stopFirst) LauncherManager.stopService(this)
            lifecycleScope.launch {
                delay(500)
                startCore(event.requireVpnPermission, event.requireLocalNetwork)
            }
            true
        }
        MainEvent.ScanQrCode -> {
            scanQrCode { text ->
                if (!text.isNullOrBlank()) viewModel.onAction(MainAction.ImportBatchConfig(text))
            }
            true
        }
        MainEvent.PickConfigFile -> {
            pickFile { uri -> uri?.let { viewModel.onAction(MainAction.ConfigFileSelected(it)) } }
            true
        }
        MainEvent.ReportFullyDrawn -> {
            reportFullyDrawn(); true
        }
        is MainEvent.ShowQrCode -> false
        is MainEvent.LocateProfile -> false
    }

    private fun startCore(requireVpnPermission: Boolean, requireLocalNetwork: Boolean) {
        if (!requireVpnPermission) return launchCore(requireLocalNetwork)
        val intent = VpnService.prepare(this)
        if (intent == null) {
            launchCore(requireLocalNetwork)
        } else {
            pendingLocalNetwork = requireLocalNetwork
            vpnPermission.launch(intent)
        }
    }

    private fun launchCore(requireLocalNetwork: Boolean) {
        if (requireLocalNetwork && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            requestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onAction(MainAction.RefreshGroups)
    }

    private companion object {
        /**
         * Generous on purpose: the first launch after an upgrade may run the whole legacy
         * import (thousands of rows) before the barrier opens.
         */
        const val BOOT_TIMEOUT_MS = 30_000L
    }
}
