package com.v2ray.ang.service

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Batches test delays into windowed profile_stats transactions.
 *
 * Every write to profile_stats invalidates the main list's PagingSource, so writing once per
 * result makes a full speed test flash the list hundreds of times. Throttling lives here, on the
 * write side; the read side never polls the database.
 */
internal class TestResultWriter(
    private val dao: ProfileDao,
    private val scope: CoroutineScope,
    private val onFlushed: (Int) -> Unit = {},
) {

    private val pending = ConcurrentHashMap<String, Long>()
    private var loop: Job? = null

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /** Cheap, non-suspend, safe from a callback thread. */
    fun record(guid: String, delayMillis: Long) {
        if (guid.isEmpty()) return
        pending[guid] = delayMillis
    }

    /** Drains everything queued so far in a single transaction. */
    suspend fun flush() {
        if (pending.isEmpty()) return
        val batch = pending.keys.toList().mapNotNull { guid ->
            pending.remove(guid)?.let { ServerAffiliationInfo(guid = guid, testDelayMillis = it) }
        }
        if (batch.isEmpty()) return
        try {
            dao.upsertStats(batch)
            onFlushed(batch.size)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to persist ${batch.size} test results", e)
        }
    }

    /** Final flush then stops the loop. Must run before anything reads the delays back. */
    suspend fun stop() {
        loop?.cancel()
        loop = null
        flush()
    }

    private companion object {
        /** Matches the pre-Room DELAY_REFRESH_INTERVAL_MS. */
        const val FLUSH_INTERVAL_MS = 400L
    }
}
