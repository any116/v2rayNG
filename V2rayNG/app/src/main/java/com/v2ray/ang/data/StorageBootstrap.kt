package com.v2ray.ang.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Process-local startup barrier for the storage layer.
 *
 * AngApplication's bootstrap coroutine completes it only after the database integrity check, the
 * legacy MMKV import, the settings snapshot refresh and (in the main process) the default-value
 * seeding have all succeeded. SettingsStore.awaitReady() is NOT a substitute: any refresh() —
 * including one kicked off by an early service start — completes that signal, so it can return
 * before the legacy import has run and before the snapshot reflects the real database.
 *
 * Failure is terminal for the process: [awaitReady] rethrows the bootstrap cause, and callers
 * must not paper over it with coded defaults (that would silently drop the user's real mode,
 * port, routing and auto-start settings). Callers that can suspend and want a bound use
 * [awaitReadyOrNull]; fire-and-forget entry points (receivers, tile, shortcuts) use it to skip
 * their action instead of proceeding half-initialised.
 */
internal object StorageBootstrap {

    /** Upper bound for callers that must give up rather than suspend forever. */
    const val DEFAULT_TIMEOUT_MS = 20_000L

    private val completion = CompletableDeferred<Unit>()

    /** Suspends until the bootstrap settled; throws when it failed. */
    suspend fun awaitReady() {
        completion.await()
    }

    /**
     * Suspends until the bootstrap settled or [timeoutMillis] elapsed. Returns true only when
     * the storage layer is fully initialised. Cancellation of the calling coroutine is rethrown,
     * never turned into "not ready"; a timeout or a bootstrap failure just logs and returns false.
     */
    suspend fun awaitReadyOrNull(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): Boolean {
        return try {
            withTimeout(timeoutMillis) { completion.await() }
            true
        } catch (e: TimeoutCancellationException) {
            LogUtil.w(
                AppConfig.TAG,
                "Storage bootstrap did not finish within ${timeoutMillis}ms; continuing is not safe"
            )
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Storage bootstrap failed", e)
            false
        }
    }

    fun complete() {
        completion.complete(Unit)
    }

    fun fail(cause: Throwable) {
        completion.completeExceptionally(cause)
    }
}
