package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. A handover is debounced before [onHandover] is invoked; losing the current network
 * dispatches [onNetworkLost] in the monitor scope so stale sockets can be discarded while waiting
 * for recovery without blocking the platform callback thread.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] and [onNetworkLost] are invoked on a background thread and may block.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: () -> Unit,
    private val onNetworkLost: () -> Unit = {},
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1000L
    }

    private val stateLock = Any()
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var upstream: Network? = null
    private var waitingForRecovery = false
    private var handoverJob: Job? = null
    private var registered = false

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val shouldReload = synchronized(stateLock) {
                val previous = upstream
                val shouldReload = (previous != null && previous != network) || waitingForRecovery
                upstream = network
                waitingForRecovery = false
                shouldReload
            }
            onUnderlyingNetworksChanged(arrayOf(network))
            if (shouldReload) {
                scheduleHandover(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val isCurrent = synchronized(stateLock) { upstream == network }
            if (isCurrent) {
                // It's a good idea to refresh capabilities of the current upstream only.
                onUnderlyingNetworksChanged(arrayOf(network))
            }
        }

        override fun onLost(network: Network) {
            val wasCurrent = synchronized(stateLock) {
                if (upstream != network) {
                    false
                } else {
                    upstream = null
                    waitingForRecovery = true
                    handoverJob?.cancel()
                    handoverJob = null
                    true
                }
            }
            if (wasCurrent) {
                onUnderlyingNetworksChanged(null)
                dispatchNetworkLost()
            }
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        synchronized(stateLock) {
            if (registered) return
            try {
                connectivity.requestNetwork(request, callback)
                registered = true
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
            }
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        val wasRegistered = synchronized(stateLock) {
            handoverJob?.cancel()
            handoverJob = null
            upstream = null
            waitingForRecovery = false
            val previous = registered
            registered = false
            previous
        }
        monitorScope.coroutineContext.cancelChildren()
        if (!wasRegistered) return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
        }
    }

    private fun dispatchNetworkLost() {
        monitorScope.launch {
            try {
                if (!synchronized(stateLock) { registered }) return@launch
                onNetworkLost()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle network loss", e)
            }
        }
    }

    private fun scheduleHandover(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        synchronized(stateLock) {
            if (!registered) return
            handoverJob?.cancel()
            handoverJob = monitorScope.launch {
                try {
                    delay(HANDOVER_DEBOUNCE_MS)
                    onHandover()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
                }
            }
        }
    }
}
