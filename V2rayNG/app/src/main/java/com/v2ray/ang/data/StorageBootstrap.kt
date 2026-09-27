package com.v2ray.ang.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Surfaced to waiters when the bootstrap failed. Deliberately NOT a CancellationException:
 * a failed bootstrap must never look like the waiting coroutine itself was cancelled.
 */
class StorageNotReadyException(cause: Throwable) :
    IllegalStateException("Storage bootstrap failed: ${cause.message}", cause)

/**
 * Process-local, retryable startup barrier for the storage layer.
 *
 * AngApplication installs the bootstrap block once per process; the barrier opens only when that
 * block (integrity check, legacy import, snapshot refresh, main-process seeding) returned
 * normally. A failure keeps the barrier closed — callers never fall back to coded defaults — but
 * it is no longer terminal: [retry] re-runs the block, so the UI retry button and cold service
 * starts can recover from transient failures without a process restart.
 */
internal object StorageBootstrap {

    /** Upper bound for callers that must give up rather than suspend forever. */
    const val DEFAULT_TIMEOUT_MS = 20_000L

    sealed interface State {
        data object Idle : State
        data object Running : State
        data object Ready : State
        data class Failed(val cause: Throwable) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Non-suspending fast path for main-thread entry points. */
    val isReady: Boolean get() = _state.value === State.Ready

    private val lock = Any()
    private var scope: CoroutineScope? = null
    private var bootstrap: (suspend () -> Unit)? = null

    /** Called exactly once, from Application.onCreate; starts the first attempt. */
    fun install(scope: CoroutineScope, bootstrap: suspend () -> Unit) {
        synchronized(lock) {
            check(this.bootstrap == null) { "StorageBootstrap already installed" }
            this.scope = scope
            this.bootstrap = bootstrap
        }
        launchAttempt()
    }

    /**
     * Starts a new attempt when the previous one failed. No-op while an attempt is running or
     * after success, so it is safe to call unconditionally before waiting.
     *
     * @return true when a new attempt was started.
     */
    fun retry(): Boolean = launchAttempt()

    private fun launchAttempt(): Boolean = synchronized(lock) {
        val scope = scope ?: return false
        val block = bootstrap ?: return false
        val current = _state.value
        if (current === State.Ready || current === State.Running) return false

        _state.value = State.Running
        scope.launch {
            try {
                block()
                _state.value = State.Ready
            } catch (e: CancellationException) {
                // Never publish the raw CancellationException: waiters would rethrow it as if
                // they had been cancelled themselves and silently drop their work.
                _state.value = State.Failed(IllegalStateException("Storage bootstrap cancelled", e))
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Storage bootstrap failed; barrier stays closed until retry()", e)
                _state.value = State.Failed(e)
            }
        }
        true
    }

    private suspend fun awaitSettled(): State =
        _state.first { it === State.Ready || it is State.Failed }

    /** Suspends until the current attempt settled; throws [StorageNotReadyException] on failure. */
    suspend fun awaitReady() {
        val settled = awaitSettled()
        if (settled is State.Failed) throw StorageNotReadyException(settled.cause)
    }

    /**
     * Bounded wait. Returns true only when storage is fully initialised. The caller's own
     * cancellation propagates normally; timeout and failure return false.
     */
    suspend fun awaitReadyOrNull(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): Boolean {
        return when (val settled = withTimeoutOrNull(timeoutMillis) { awaitSettled() }) {
            State.Ready -> true
            is State.Failed -> {
                LogUtil.e(AppConfig.TAG, "Storage bootstrap failed", settled.cause)
                false
            }
            else -> {
                LogUtil.w(
                    AppConfig.TAG,
                    "Storage bootstrap did not finish within ${timeoutMillis}ms; continuing is not safe"
                )
                false
            }
        }
    }
}
