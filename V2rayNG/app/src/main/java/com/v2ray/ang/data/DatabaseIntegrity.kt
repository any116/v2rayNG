package com.v2ray.ang.data

import android.app.Application
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Runs PRAGMA integrity_check before Room opens the database. A corrupt database still builds
 * successfully through Room; every query then throws, the UI renders an empty list, and the
 * user has no way to tell what happened.
 *
 * Must be called inside LegacyMigrationGate's file lock and BEFORE this process resolves
 * AppDatabase for the first time. Once Room has opened the file, renaming it leaves a dangling
 * fd, so the quarantine below is only safe if it runs first.
 *
 * Runs once per process — the caller (LegacyMigrationGate.runIfNeeded) is invoked once from
 * Application.onCreate's bootstrap coroutine, so the cost is paid once at cold start.
 */
internal object DatabaseIntegrity {

    private const val QUARANTINE_DIR = "corrupt"

    fun verifyOrQuarantine(app: Application) {
        val dbFile = app.getDatabasePath(AppDatabase.NAME)
        if (!dbFile.isFile) return

        val healthy = runCatching {
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                conn.prepare("PRAGMA integrity_check").use { st ->
                    st.step() && st.getText(0).equals("ok", ignoreCase = true)
                }
            }
        }.getOrElse {
            LogUtil.e(AppConfig.TAG, "integrity_check could not run; treating as corrupt", it)
            false
        }
        if (healthy) return

        val dir = File(dbFile.parentFile, QUARANTINE_DIR).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        listOf("", "-wal", "-shm").forEach { suffix ->
            val src = File("${dbFile.path}$suffix")
            if (!src.exists()) return@forEach
            val dst = File(dir, "${dbFile.name}$suffix.$stamp")
            if (!src.renameTo(dst)) src.delete()
        }
        // The MMKV legacy store is still on disk (MmkvLegacyReader only reads, never deletes),
        // and LEGACY_IMPORT_STATE disappeared with the corrupt database, so the rebuilt empty
        // database re-triggers a full import. This is the strongest argument for keeping MMKV
        // around for at least one more release: without it, this path has no data source to
        // rebuild from and would have to fall back to WebDAV restore.
        LogUtil.w(AppConfig.TAG, "Corrupt database quarantined into $QUARANTINE_DIR; rebuilding")
    }
}
