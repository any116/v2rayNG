package com.v2ray.ang.data

import android.app.Application
import androidx.room3.PooledConnection
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.legacy.LegacyReadException
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.data.legacy.MmkvLegacyReader
import com.v2ray.ang.data.repository.BackupRepository
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

internal fun interface SqlExec {
    suspend operator fun invoke(sql: String, bind: SQLiteStatement.() -> Unit)
}

internal fun PooledConnection.asSqlExec() = SqlExec { sql, bind ->
    usePrepared(sql) { st -> st.bind(); st.step() }
}

enum class StorageMode(val label: String) {
    ROOM("room"),
    MMKV("mmkv"),
}

internal object LegacyMigrationGate {

    private const val KEY_STATE = "LEGACY_IMPORT_STATE"
    private const val STATE_DONE = "done"
    private const val LOCK_FILE = "legacy_import.lock"

    /** In-process mutex. Cross-process mutual exclusion is handled by [withProcessLock]; both layers are indispensable. */
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

    /**
     * Cross-process mutual exclusion. :daemon / :bg / :tasks each have their own Hilt graph and their own instance of this object,
     * so the Mutex only guards this process. Always-on VPN, boot broadcasts, Tile, and Glance widget can all cause the service process
     * to start before the UI process, and two processes entering the import simultaneously is a realistic path.
     *
     * FileLock is JVM-level: reentrancy within the same process throws OverlappingFileLockException,
     * so the caller must already hold [lock].
     */
    private suspend fun <T> withProcessLock(
        context: Application,
        io: CoroutineDispatcher,
        block: suspend () -> T,
    ): T = withContext(io) {
        RandomAccessFile(File(context.filesDir, LOCK_FILE), "rw").use { raf ->
            val fileLock = raf.channel.lock()   // blocks until the other process releases
            try {
                block()
            } finally {
                runCatching { fileLock.release() }
            }
        }
    }

    suspend fun runIfNeeded(
        context: Application,
        db: AppDatabase,
        settings: SettingsStore,
        io: CoroutineDispatcher,
    ): Boolean = lock.withLock {
        withProcessLock(context, io) {
            DatabaseIntegrity.verifyOrQuarantine(context)

            val dao = db.settingsDao()
            if (dao.value(KEY_STATE) == STATE_DONE) return@withProcessLock true

            val staged = BackupRepository.pendingSnapshotFile(context)
            val fromStaged = staged.isFile

            val snapshot = if (fromStaged) {
                try {
                    JsonUtil.fromJsonSafe(staged.readText(), LegacySnapshot::class.java)
                        ?: error("Pending legacy snapshot failed to deserialize")
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Pending legacy snapshot unreadable; kept for retry", e)
                    return@withProcessLock false
                }
            } else {
                val reader = MmkvLegacyReader(context)
                if (!reader.hasLegacyStore()) {
                    finish(db, settings, staged = null)
                    return@withProcessLock true
                }
                try {
                    reader.readAll()
                } catch (e: LegacyReadException) {
                    LogUtil.e(AppConfig.TAG, "Legacy store unreadable; will retry next launch", e)
                    return@withProcessLock false
                }
            }

            if (snapshot.isEmpty) {
                finish(db, settings, staged = staged.takeIf { fromStaged })
                return@withProcessLock true
            }

            try {
                db.useWriterConnection { conn ->
                    conn.immediateTransaction {
                        val before = readCountsIn(conn)
                        LegacyImporter.importInto(conn.asSqlExec(), snapshot)
                        val after = readCountsIn(conn)

                        val expected = snapshot.expectedCounts()
                        val mismatch = expected.filterKeys { table ->
                            after.of(table) - before.of(table) != expected.getValue(table)
                        }
                        if (mismatch.isNotEmpty()) {
                            error(
                                "Legacy import mismatch on ${mismatch.keys.joinToString()}: " +
                                    "expected[${expected.entries.joinToString { "${it.key}=${it.value}" }}] " +
                                    "delta[${COUNTED_TABLES.joinToString { "$it=${after.of(it) - before.of(it)}" }}]"
                            )
                        }

                        conn.usePrepared(
                            "INSERT OR REPLACE INTO settings(key, value, kind) VALUES (?,?,?)"
                        ) { st ->
                            st.bindText(1, KEY_STATE)
                            st.bindText(2, STATE_DONE)
                            st.bindText(3, SettingsStore.KIND_STRING)
                            st.step()
                        }
                    }
                }
                // Only reaching this point means the transaction has been committed.
                settings.poke(KEY_STATE, STATE_DONE)
                if (fromStaged) staged.delete()
                LogUtil.i(AppConfig.TAG, "Legacy import finished and verified")
                true
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Legacy import rolled back; retry next launch", e)
                false
            }
        }
    }

    /** Cleanup when no import is needed: mark + snapshot + clear staging, in the same order as the import path. */
    private suspend fun finish(db: AppDatabase, settings: SettingsStore, staged: File?) {
        db.settingsDao().upsert(
            com.v2ray.ang.data.entities.SettingsEntry(KEY_STATE, STATE_DONE, SettingsStore.KIND_STRING)
        )
        settings.poke(KEY_STATE, STATE_DONE)
        staged?.delete()
    }

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
     * Pure reads, using a read connection. Under WAL, reads and writes can proceed in parallel; occupying the database's
     * single write connection to run seven COUNT(*) queries would block the entire write path during imports/subscription updates.
     */
    private suspend fun readCounts(db: AppDatabase): TableCounts = db.useReaderConnection { conn ->
        TableCounts(COUNTED_TABLES.associateWith { conn.countOf(it) })
    }

    /**
     * In-transaction version. Takes the PooledConnection rather than relying on a receiver: the
     * TransactionScope that immediateTransaction passes in is not a PooledConnection and does
     * not expose usePrepared, caller passes `conn` explicitly.
     */
    private suspend fun readCountsIn(conn: PooledConnection): TableCounts =
        TableCounts(COUNTED_TABLES.associateWith { conn.countOf(it) })

    private suspend fun PooledConnection.countOf(table: String): Long =
        usePrepared("SELECT COUNT(*) FROM $table") { st -> if (st.step()) st.getLong(0) else 0L }

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
            "settings" to (settings.map { it.key }.distinct().size + 1).toLong()
        )
    }

    private class TableCounts(private val values: Map<String, Long>) {
        fun of(table: String): Long = values[table] ?: 0L
        override fun toString(): String = values.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }
}
