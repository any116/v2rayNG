package com.v2ray.ang.data

import android.app.Application
import androidx.room3.PooledConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.SettingsEntry
import com.v2ray.ang.data.legacy.LegacyReadException
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.data.legacy.MmkvLegacyReader
import com.v2ray.ang.data.repository.BackupRepository
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface SqlExec {
    suspend operator fun invoke(sql: String, bind: SQLiteStatement.() -> Unit)
}

internal fun PooledConnection.asSqlExec() = SqlExec { sql, bind ->
    usePrepared(sql) { st -> st.bind(); st.step() }
}

/**
 * Which store the app's data effectively lives in right now. Reads and writes always go
 * through Room; MMKV means the legacy store still holds data Room has not imported yet, so
 * the process is running on an incomplete picture.
 */
enum class StorageMode(val label: String) {
    ROOM("room"),
    MMKV("mmkv"),
}

internal object LegacyMigrationGate {

    private const val KEY_STATE = "LEGACY_IMPORT_STATE"
    private const val STATE_DONE = "done"
    private val lock = Mutex()

    private val COUNTED_TABLES = listOf(
        "profiles",
        "profile_stats",
        "profile_raw",
        "subscriptions",
        "assets",
        "routing_rules",
        "settings",
    )

    suspend fun runIfNeeded(
        context: Application,
        db: AppDatabase,
        settings: SettingsStore,
    ): Boolean = lock.withLock {
        val dao = db.settingsDao()
        if (dao.value(KEY_STATE) == STATE_DONE) return true

        val staged = BackupRepository.pendingSnapshotFile(context)
        val snapshot = if (staged.isFile) {
            try {
                val json = staged.readText()
                staged.delete()
                JsonUtil.fromJsonSafe(json, LegacySnapshot::class.java)
                    ?: error("Pending legacy snapshot failed to deserialize")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Pending legacy snapshot unreadable; will retry next launch", e)
                return false
            }
        } else {
            val reader = MmkvLegacyReader(context)
            if (!reader.hasLegacyStore()) {
                markDone(dao, settings)
                return true
            }
            try {
                reader.readAll()
            } catch (e: LegacyReadException) {
                LogUtil.e(AppConfig.TAG, "Legacy store unreadable; will retry next launch", e)
                return false
            }
        }

        if (snapshot.isEmpty) {
            markDone(dao, settings)
            return true
        }

        return try {
            db.useWriterConnection { conn ->
                LegacyImporter.importInto(conn.asSqlExec(), snapshot)
            }
            // Runs before markDone so LEGACY_IMPORT_STATE is not counted as an imported row.
            verifyImport(db, snapshot)
            markDone(dao, settings)
            LogUtil.i(AppConfig.TAG, "Legacy import finished")
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Legacy import failed; database left empty", e)
            false
        }
    }

    /**
     * One line per process start naming the store that is actually serving data, plus the
     * Room row counts. Emitted at warn level while a legacy import is still outstanding,
     * because that state silently hides profiles from the main list.
     */
    suspend fun logStorageMode(context: Application, db: AppDatabase, isMainProcess: Boolean) {
        val done = runCatching { db.settingsDao().value(KEY_STATE) == STATE_DONE }
            .getOrElse {
                LogUtil.e(AppConfig.TAG, "Storage mode probe failed", it)
                return
            }
        val legacyPresent = runCatching { MmkvLegacyReader(context).hasLegacyStore() }
            .getOrDefault(false)
        val rows = runCatching { readCounts(db) }.getOrNull()

        val mode = if (done) StorageMode.ROOM else StorageMode.MMKV
        val line = buildString {
            append("Storage mode=").append(mode.label)
            append(" process=").append(if (isMainProcess) "main" else "secondary")
            append(" legacyStore=").append(if (legacyPresent) "present" else "absent")
            append(" rows=[").append(rows ?: "unavailable").append(']')
        }
        if (mode == StorageMode.ROOM) LogUtil.i(AppConfig.TAG, line) else LogUtil.w(AppConfig.TAG, line)
    }

    /**
     * Compares what the snapshot carried against what the tables hold. A mismatch is the only
     * signal that separates "nothing to import" from "the import dropped rows", and the
     * previous version had no way to tell those apart.
     */
    private suspend fun verifyImport(db: AppDatabase, snapshot: LegacySnapshot) {
        val expected = snapshot.expectedCounts()
        val actual = runCatching { readCounts(db) }.getOrElse {
            LogUtil.e(AppConfig.TAG, "Legacy import verification could not read row counts", it)
            return
        }
        val mismatch = expected.filter { (table, count) -> actual.of(table) != count }
        val expectedLine = expected.entries.joinToString(", ") { "${it.key}=${it.value}" }
        if (mismatch.isEmpty()) {
            LogUtil.i(AppConfig.TAG, "Legacy import verified: mmkv[$expectedLine] -> room[$actual]")
        } else {
            LogUtil.w(
                AppConfig.TAG,
                "Legacy import mismatch on ${mismatch.keys.joinToString()}: " +
                    "mmkv[$expectedLine] -> room[$actual]"
            )
        }
    }

    private suspend fun readCounts(db: AppDatabase): TableCounts = db.useWriterConnection { conn ->
        TableCounts(
            COUNTED_TABLES.associateWith { table ->
                conn.usePrepared("SELECT COUNT(*) FROM $table") { st ->
                    if (st.step()) st.getLong(0) else 0L
                }
            }
        )
    }

    /**
     * Distinct keys everywhere, because LegacyImporter uses INSERT OR REPLACE: a guid listed
     * in two group indexes yields one row, not two.
     */
    private fun LegacySnapshot.expectedCounts(): Map<String, Long> {
        val profileGuids = groups.values.flatten().map { it.first }.distinct().size
        val namedRules = rulesets.filter { it.id.isNotBlank() }.map { it.id }.distinct().size
        val unnamedRules = rulesets.count { it.id.isBlank() }
        val subCount = subscriptions.keys.size +
            if (AppConfig.DEFAULT_SUBSCRIPTION_ID in subscriptions) 0 else 1
        return mapOf(
            "profiles" to profileGuids.toLong(),
            "profile_stats" to stats.keys.size.toLong(),
            "profile_raw" to raws.keys.size.toLong(),
            "subscriptions" to subCount.toLong(),
            "assets" to assets.map { it.guid }.distinct().size.toLong(),
            "routing_rules" to (namedRules + unnamedRules).toLong(),
            // +1 for DEDUPE_ALGO_VERSION, written by the importer itself.
            "settings" to (settings.map { it.key }.distinct().size + 1).toLong(),
        )
    }

    private suspend fun markDone(dao: SettingsDao, settings: SettingsStore) {
        dao.upsert(SettingsEntry(KEY_STATE, STATE_DONE, SettingsStore.KIND_STRING))
        settings.poke(KEY_STATE, STATE_DONE)
    }

    private class TableCounts(private val values: Map<String, Long>) {
        fun of(table: String): Long = values[table] ?: 0L
        override fun toString(): String = values.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }
}
