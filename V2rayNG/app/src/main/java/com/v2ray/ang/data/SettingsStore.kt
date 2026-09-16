package com.v2ray.ang.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.SettingsEntry
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process synchronous snapshot of scalar preferences.
 *
 * Reads are synchronous, non blocking and never touch the database; refresh() must have run
 * first. Writes are suspend and poke the local snapshot immediately. Cross process consistency
 * is eventual: other processes catch up through the settings table invalidation. Paths that
 * need a strong guarantee (core startup) call refresh() explicitly as their first step.
 *
 * Callers that need the snapshot but cannot assume refresh() has already completed (UI startup
 * gate, first-time consumers) suspend on awaitReady() instead of racing the bootstrap coroutine
 * and reading defaults. awaitReady() is guaranteed to return: refresh() completes the signal in
 * a finally block, so a database failure degrades to coded defaults instead of hanging the UI.
 *
 * The DAO and the database are injected directly. An earlier version wrapped them in
 * Provider<...> to defer resolution past Application construction; that was based on the
 * mistaken belief that resolving the singleton would open the database file. It does not —
 * Room.databaseBuilder(...).build() is lazy, and AngApplication already resolves the same
 * @Singleton on the main thread anyway. Do not reintroduce the Provider here.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dao: SettingsDao,
    private val db: AppDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val snapshot = ConcurrentHashMap<String, String>()
    private val ready = AtomicBoolean(false)

    /**
     * Guards multi-step operations on [snapshot]: refresh()'s putAll+retainAll pair, and poke().
     *
     * Single reads against the ConcurrentHashMap are still safe on their own, but a write that
     * lands between putAll and retainAll would be silently dropped by the retainAll — that is
     * exactly the window writeAsync opens when it pokes first and persists later.
     */
    private val snapshotLock = Any()

    /** Completed by the first refresh(), success or failure; awaitReady() suspends on it. */
    private val readySignal = CompletableDeferred<Unit>()

    /** Cause of the last refresh failure, or null when the snapshot reflects the database. */
    @Volatile
    var degradedCause: Throwable? = null
        private set

    val isDegraded: Boolean get() = degradedCause != null

    /** Owns fire and forget writes issued from non suspend call sites. */
    private val writeScope = CoroutineScope(SupervisorJob() + io)

    val isReady: Boolean get() = ready.get()

    /**
     * Suspends until the snapshot has been populated at least once.
     */
    suspend fun awaitReady() = readySignal.await()

    suspend fun refresh() {
        try {
            val rows = dao.all()
            val fresh = HashMap<String, String>(rows.size)
            rows.forEach { row -> row.value?.let { fresh[row.key] = it } }
            synchronized(snapshotLock) {
                snapshot.putAll(fresh)
                snapshot.keys.retainAll(fresh.keys)
            }
            degradedCause = null
        } catch (t: Throwable) {
            degradedCause = t
            LogUtil.e(AppConfig.TAG, "SettingsStore.refresh failed; running on coded defaults", t)
            throw t
        } finally {
            ready.set(true)
            readySignal.complete(Unit)
        }
    }

    /**
     * Keeps this process' snapshot aligned with writes made by any other process.
     */
    fun observe(scope: CoroutineScope): Job = scope.launch(io) {
        db.invalidationTracker.createFlow(TABLE, emitInitialState = false)
            .conflate()
            .collect { runCatching { refresh() } }
    }

    /**
     * Writes the coded defaults for keys that are absent or blank.
     */
    suspend fun seedDefaults() {
        val missing = SettingsDefaults.ENTRIES.filter { read(it.key).isNullOrBlank() }
        if (missing.isEmpty()) return
        dao.upsertAll(missing.map { SettingsEntry(it.key, it.value, it.kind) })
        // Go through poke so the snapshot lock is respected, matching refresh()'s contract.
        missing.forEach { poke(it.key, it.value) }
    }

    private fun read(key: String): String? {
        if (!ready.get()) {
            LogUtil.w(AppConfig.TAG, "SettingsStore read before refresh(): $key")
            return null
        }
        return snapshot[key]
    }

    // ---- Synchronous reads, one to one with MmkvManager.decodeSettingsXxx ----

    fun contains(key: String): Boolean = read(key) != null

    fun bool(key: String, default: Boolean = false): Boolean =
        read(key)?.toBooleanStrictOrNull() ?: default

    fun string(key: String, default: String? = null): String? = read(key) ?: default

    fun int(key: String, default: Int = 0): Int = read(key)?.toIntOrNull() ?: default

    fun long(key: String, default: Long = 0L): Long = read(key)?.toLongOrNull() ?: default

    fun float(key: String, default: Float = 0f): Float = read(key)?.toFloatOrNull() ?: default

    fun stringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        val raw = read(key) ?: return default
        return JsonUtil.fromJsonSafe(raw, Array<String>::class.java)?.toSet() ?: default
    }

    // ---- Suspend writes ----

    suspend fun putBool(key: String, value: Boolean) = put(key, value.toString(), KIND_BOOL)

    suspend fun putString(key: String, value: String?) = put(key, value, KIND_STRING)

    suspend fun putInt(key: String, value: Int) = put(key, value.toString(), KIND_INT)

    suspend fun putLong(key: String, value: Long) = put(key, value.toString(), KIND_LONG)

    suspend fun putFloat(key: String, value: Float) = put(key, value.toString(), KIND_FLOAT)

    suspend fun putStringSet(key: String, value: Set<String>?) =
        put(key, value?.let { JsonUtil.toJson(it.toList()) }, KIND_SET)

    private suspend fun put(key: String, value: String?, kind: String) = withContext(io) {
        if (value == null) dao.delete(key) else dao.upsert(SettingsEntry(key, value, kind))
        poke(key, value)
    }

    // ---- Fire and forget writes ----

    /**
     * For object singletons that persist a preference from a non suspend callback (locale
     * handoff, widget state). The snapshot is updated before the coroutine is scheduled, so a
     * synchronous read right after this call already sees the new value.
     */
    fun writeAsync(key: String, value: String?, kind: String): Job {
        poke(key, value)
        return writeScope.launch {
            runCatching { put(key, value, kind) }
                .onFailure { LogUtil.e(AppConfig.TAG, "Failed to persist setting $key", it) }
        }
    }

    fun setBoolAsync(key: String, value: Boolean) = writeAsync(key, value.toString(), KIND_BOOL)

    fun setStringAsync(key: String, value: String?) = writeAsync(key, value, KIND_STRING)

    fun setIntAsync(key: String, value: Int) = writeAsync(key, value.toString(), KIND_INT)

    fun setLongAsync(key: String, value: Long) = writeAsync(key, value.toString(), KIND_LONG)

    /** Memory only patch, for writes that happen inside another DAO transaction. */
    fun poke(key: String, value: String?) {
        synchronized(snapshotLock) {
            if (value == null) snapshot.remove(key) else snapshot[key] = value
        }
    }

    companion object {
        const val TABLE = "settings"
        const val KIND_BOOL = "b"
        const val KIND_STRING = "s"
        const val KIND_INT = "i"
        const val KIND_LONG = "l"
        const val KIND_FLOAT = "f"
        const val KIND_SET = "set"

        const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
        const val KEY_WEBDAV_CONFIG = "WEBDAV_CONFIG"
        const val KEY_DEDUPE_ALGO_VERSION = "DEDUPE_ALGO_VERSION"
    }
}
