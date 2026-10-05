package com.v2ray.ang.data.repository

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.StorageBootstrap
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.LogcatRecord
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class LogcatRepository @Inject constructor(
    private val app: Application,
    private val settings: SettingsStore,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    private val bufferLock = Mutex()
    private val recordIds = LogcatRecordIds()
    private val exports by lazy { LogcatExports(app.cacheDir) }

    open fun createPager(): LogcatPager = LogcatPager()

    /** Match a fresh logd snapshot to prior records without IO or retained log history. */
    open fun identify(raw: List<String>, previous: List<LogcatRecord>): List<LogcatRecord> =
        recordIds.identify(raw, previous)

    open suspend fun read(): List<String> = withIO {
        StorageBootstrap.awaitReady()
        bufferLock.withLock {
            exports.prune(System.currentTimeMillis())
            val buffer = try {
                readLogcatProcess(logcatReadCommand(AppConfig.TAG))
            } catch (e: TimeoutCancellationException) {
                throw IOException("Timed out reading logcat", e)
            }
            val now = System.currentTimeMillis()
            buffer.snapshot(now, baseline(now))
        }
    }

    /** The persisted watermark is authoritative; clearing never needs logd privileges. */
    open suspend fun clear(): Boolean = withIO {
        StorageBootstrap.awaitReady()
        bufferLock.withLock {
            withContext(NonCancellable) {
                settings.putLong(AppConfig.CACHE_LOGCAT_CLEARED_AT, System.currentTimeMillis())
                exports.clear()
            }
            true
        }
    }

    open fun retain(lines: List<String>): List<String> {
        val now = System.currentTimeMillis()
        return retainLogcatLines(lines, now, baseline(now))
    }

    /** Revalidate expiry and capacity without changing surviving identities or collapsing duplicates. */
    open fun retainRecords(records: List<LogcatRecord>): List<LogcatRecord> {
        val remaining = retain(records.map { it.raw }).groupingBy { it }.eachCount().toMutableMap()
        return records.filter { record ->
            val count = remaining[record.raw] ?: 0
            if (count > 0) remaining[record.raw] = count - 1
            count > 0
        }
    }

    open suspend fun writeShareFile(lines: List<String>): String? = runIO(null) {
        bufferLock.withLock {
            val retained = retain(lines)
            if (retained.isEmpty()) return@withLock null
            exports.write(retained, System.currentTimeMillis()).absolutePath
        }
    }

    open suspend fun copyToClipboard(text: String) = withIO { Utils.setClipboard(app, text) }

    private fun baseline(now: Long): Long =
        logcatClearedAt(settings.string(AppConfig.CACHE_LOGCAT_CLEARED_AT), now)
}
