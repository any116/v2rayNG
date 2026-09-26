package com.v2ray.ang.service

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

internal object RealPingExecutionLimiter {
    private val customConfigMutex = Mutex()

    suspend fun <T> run(configType: EConfigType, block: () -> T): T {
        // Custom profiles bypass speed-test trimming and start complete Xray configs. Parallel
        // teardown can abort the native probe process, so serialize their JNI measurements
        // globally across batches.
        return if (configType == EConfigType.CUSTOM) {
            customConfigMutex.withLock { block() }
        } else {
            block()
        }
    }
}

/**
 * Runs one batch of real-ping tests. Each batch owns its scope/dispatcher and can be cancelled
 * independently.
 *
 * Why a fixed worker pool instead of one coroutine per guid: the probe path suspends (Room
 * lookups, config building, the CUSTOM mutex). With one coroutine per guid every suspension
 * frees a pool thread and lets the next guid start, so all N targets enter the DAO at once and
 * then advance in lock-step through the FIFO dispatcher queue — the first result only arrives
 * after every target has been prepared, and the "running" counter climbs to N instead of the
 * configured concurrency. Exactly [workerCount] coroutines pull guids from a shared index, so
 * in-flight work, pool threads and the displayed count all equal the setting.
 *
 * @param concurrency resolved by the caller after the settings snapshot has been refreshed;
 *                    reading it in a property initializer raced the :tasks process bootstrap.
 */
class RealPingWorkerService(
    private val context: Context,
    private val profileDao: ProfileDao,
    private val guids: List<String>,
    concurrency: Int,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val total = guids.size

    /** Never more workers (and threads) than targets. TCP-only probes are cheap, so they get 2x. */
    private val workerCount = (if (onlyTcp) concurrency * 2 else concurrency)
        .coerceAtLeast(1)
        .coerceAtMost(total.coerceAtLeast(1))

    /** Read once per batch: the caller refreshed the snapshot right before constructing us. */
    private val testUrl = SettingsManager.getDelayTestUrl()

    private val job = SupervisorJob()
    private val dispatcher = Executors.newFixedThreadPool(workerCount, RealPingThreadFactory)
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val nextIndex = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val lastProgressAt = AtomicLong(0L)

    fun start() {
        if (total == 0) {
            onEvent(RealPingEvent.Finish)
            close()
            return
        }
        // Cancelled between construction and start: never spin up workers.
        if (!scope.isActive) {
            close()
            return
        }

        // Immediate feedback, instead of a bare "testing" until the first probe settles.
        emitProgress(done = 0, force = true)

        val workers = List(workerCount) { scope.launch { runWorker() } }

        // ATOMIC: even if cancelled before its first dispatch, this still reaches finally and
        // releases the pool threads.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                workers.joinAll()
                if (isActive) {
                    onEvent(RealPingEvent.Finish)
                }
            } catch (_: CancellationException) {
                // Cancelled batches are reported by the owner as a cancel, not as a finish.
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private suspend fun runWorker() {
        while (currentCoroutineContext().isActive) {
            val index = nextIndex.getAndIncrement()
            if (index >= total) return
            val guid = guids[index]

            val delay = try {
                if (onlyTcp) startTcping(guid) else startRealPing(guid)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LogUtil.e(AppConfig.TAG, "Real ping failed for $guid", t)
                -1L
            }

            if (!scope.isActive) return
            onEvent(RealPingEvent.Result(guid, delay))
            emitProgress(completedCount.incrementAndGet())
        }
    }

    /**
     * "running / remaining". With a saturated worker pool the running count is exactly
     * min(workerCount, remaining), so it always matches the concurrency setting.
     * Throttled: Android drops notification updates beyond a few per second anyway, and every
     * emission is also a cross-process broadcast. The start and the last tick are never dropped.
     */
    private fun emitProgress(done: Int, force: Boolean = false) {
        if (!scope.isActive) return
        val remaining = (total - done).coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()
        if (!force && remaining > 0) {
            val last = lastProgressAt.get()
            if (now - last < PROGRESS_INTERVAL_MS || !lastProgressAt.compareAndSet(last, now)) return
        } else {
            lastProgressAt.set(now)
        }
        val running = min(workerCount, remaining)
        onEvent(RealPingEvent.Progress("$running / $remaining"))
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private suspend fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = profileDao.findByGuid(guid) ?: return retFailure
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            if (SpeedtestManager.socketConnectTime(url, port, TCP_PRECHECK_TIMEOUT_MS) <= -1L) {
                return retFailure
            }
        }

        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return RealPingExecutionLimiter.run(config.configType) {
            CoreNativeManager.measureOutboundDelay(configResult.content, testUrl)
        }
    }

    private suspend fun startTcping(guid: String): Long {
        val retFailure = -1L

        val config = profileDao.findByGuid(guid) ?: return retFailure
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            return SpeedtestManager.socketConnectTime(
                config.server.orEmpty(),
                config.serverPort.orEmpty().toInt(),
                TCP_PRECHECK_TIMEOUT_MS
            )
        }

        return retFailure
    }

    private object RealPingThreadFactory : ThreadFactory {
        private val seq = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread =
            Thread(r, "RealPing-${seq.incrementAndGet()}").apply { isDaemon = true }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 200L
        const val TCP_PRECHECK_TIMEOUT_MS = 1000
    }
}
