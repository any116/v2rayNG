package com.v2ray.ang.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.SettingsEntry
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process synchronous snapshot of scalar preferences.
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dao: SettingsDao,
    private val db: AppDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val snapshot = ConcurrentHashMap<String, String>()
    private val ready = AtomicBoolean(false)

    val isReady: Boolean get() = ready.get()

    suspend fun refresh() {
        val rows = dao.all()
        val fresh = HashMap<String, String>(rows.size)
        rows.forEach { row -> row.value?.let { fresh[row.key] = it } }
        snapshot.keys.retainAll(fresh.keys)
        snapshot.putAll(fresh)
        ready.set(true)
    }

    /** Keeps this process' snapshot aligned with writes made by any other process. */
    fun observe(scope: CoroutineScope): Job = scope.launch(io) {
        db.invalidationTracker.createFlow(TABLE, emitInitialState = false).collect { refresh() }
    }

    private fun read(key: String): String? {
        if (!ready.get()) {
            LogUtil.w(AppConfig.TAG, "SettingsStore read before refresh(): $key")
            return null
        }
        return snapshot[key]
    }

    // ---- Synchronous reads, one to one with MmkvManager.decodeSettingsXxx ----

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

    fun poke(key: String, value: String?) {
        if (value == null) snapshot.remove(key) else snapshot[key] = value
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
