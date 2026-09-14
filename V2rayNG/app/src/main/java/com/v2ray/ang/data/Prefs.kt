package com.v2ray.ang.data

import com.v2ray.ang.AngApplication
import com.v2ray.ang.di.PlatformDependencies

/**
 * Synchronous preference access for process wide singletons (handler/, core/, service/) that
 * have no constructor for Hilt to inject into. Classes with constructor injection must take
 * SettingsStore directly instead of reaching through this facade.
 *
 * Reads are snapshot reads and never touch the database. Writes are asynchronous: the snapshot
 * is updated first, so a read right after a write is already correct in this process.
 */
object Prefs {

    private val store: SettingsStore
        get() = PlatformDependencies.settingsStore(AngApplication.application)

    fun bool(key: String, default: Boolean = false): Boolean = store.bool(key, default)

    fun string(key: String, default: String? = null): String? = store.string(key, default)

    fun int(key: String, default: Int = 0): Int = store.int(key, default)

    fun long(key: String, default: Long = 0L): Long = store.long(key, default)

    fun float(key: String, default: Float = 0f): Float = store.float(key, default)

    fun stringSet(key: String, default: Set<String> = emptySet()): Set<String> =
        store.stringSet(key, default)

    fun setBool(key: String, value: Boolean) { store.setBoolAsync(key, value) }

    fun setString(key: String, value: String?) { store.setStringAsync(key, value) }

    fun setInt(key: String, value: Int) { store.setIntAsync(key, value) }

    fun setLong(key: String, value: Long) { store.setLongAsync(key, value) }

    suspend fun putBool(key: String, value: Boolean) = store.putBool(key, value)

    suspend fun putString(key: String, value: String?) = store.putString(key, value)

    val isReady: Boolean get() = store.isReady
}
