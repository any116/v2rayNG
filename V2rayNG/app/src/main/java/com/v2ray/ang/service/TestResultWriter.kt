package com.v2ray.ang.service

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Batches test delays into windowed profile_stats transactions.
 *
 * Every write to profile_stats invalidates the main list's PagingSource, so writing once per
 * result makes a full speed test flash the list hundreds of times. Throttling lives here, on the
 * write side; the read side never polls the database.
 *
 * flush() is a barrier, not just a drain: the mutex covers taking the batch AND committing it,
 * and the empty-queue check runs under the same lock. An explicit final flush therefore cannot
 * slip past a windowed flush that is still mid-commit — which is what post-processing (sorting,
 * invalid-node removal) relies on. Failures requeue the batch and propagate; the windowed loop
 * logs and retries next window, explicit callers (finish / stop) must handle them.
 */
internal class TestResultWriter(
    private val dao: ProfileDao,
    private val scope: CoroutineScope,
    private val onFlushed: (Int) -> Unit = {},
) {

    private val pending = ConcurrentHashMap<String, Long>()
    private val flushMutex = Mutex()

    @Volatile
    private var loop: Job? = null

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                try {
                    flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The batch was requeued; the next window retries it.
                    LogUtil.e(AppConfig.TAG, "Scheduled test-result flush failed; retrying next window", e)
                }
            }
        }
    }

    /** Cheap, non-suspend, safe from a callback thread. */
    fun record(guid: String, delayMillis: Long) {
        if (guid.isEmpty()) return
        pending[guid] = delayMillis
    }

    /**
     * Drains everything queued so far, waiting for any flush that is already in flight.
     * When this returns normally, every result recorded before the call has been committed.
     */
    suspend fun flush() {
        flushMutex.withLock {
            val batch = pending.keys.toList().mapNotNull { guid ->
                pending.remove(guid)?.let { ServerAffiliationInfo(guid = guid, testDelayMillis = it) }
            }
            if (batch.isEmpty()) return@withLock

            // One unit of work: a cancellation waiting at this point must not tear the commit
            // apart halfway through, and a failed write must not lose the batch.
            withContext(NonCancellable) {
                try {
                    dao.upsertStats(batch)
                } catch (e: Exception) {
                    // Requeue without overwriting results recorded while the batch was being
                    // written; the failure propagates so the caller can react.
                    batch.forEach { row -> pending.putIfAbsent(row.guid, row.testDelayMillis) }
                    throw e
                }
            }

            onFlushed(batch.size)
        }
    }

    /**
     * Final flush then stops the loop. Must run before anything reads the delays back.
     * Runs in [NonCancellable]: the cleanup of a dying service must still complete.
     */
    suspend fun stop() {
        withContext(NonCancellable) {
            val timer = loop
            loop = null
            timer?.cancelAndJoin()
            flush()
        }
    }

    private companion object {
        /** Matches the pre-Room DELAY_REFRESH_INTERVAL_MS. */
        const val FLUSH_INTERVAL_MS = 400L
    }
}
