package com.v2ray.ang.data

import android.app.Application
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Runs PRAGMA integrity_check before Room opens the database. A corrupt database still builds
 * successfully through Room; every query then throws, the UI renders an empty list, and the
 * user has no way to tell what happened.
 *
 * Must be called inside LegacyMigrationGate's file lock and BEFORE this process resolves
 * AppDatabase for the first time.
 *
 * The check only reports; it never moves, renames or deletes anything. "The check could not
 * run" (lock contention, I/O error) is not "the database is corrupt", and quarantining a live
 * file while other processes still hold Room connections — or deleting it when the rename
 * fails — can destroy healthy user data. The legacy MMKV store cannot reconstruct rows written
 * after the migration either, so a rebuild would be lossy regardless. A failing check
 * therefore aborts the storage bootstrap and the files stay on disk for an explicit,
 * offline recovery.
 *
 * Runs once per process — the caller (LegacyMigrationGate.runIfNeeded) is invoked once from
 * Application.onCreate's bootstrap coroutine, so the cost is paid once at cold start.
 */
internal object DatabaseIntegrity {

    fun verifyOrThrow(app: Application) {
        val dbFile = app.getDatabasePath(AppDatabase.NAME)
        if (!dbFile.isFile) return

        val results = try {
            BundledSQLiteDriver().open(dbFile.absolutePath).use { conn ->
                // Another process may hold a short-lived lock; wait instead of failing the
                // check on the first busy result.
                conn.prepare("PRAGMA busy_timeout = 3000").use { st -> st.step() }

                conn.prepare("PRAGMA integrity_check").use { st ->
                    buildList<String> {
                        while (st.step()) {
                            add(st.getText(0))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Database integrity check unavailable; files preserved", e)
            throw e
        }

        check(results.size == 1 && results.single().equals("ok", ignoreCase = true)) {
            "Database integrity check failed; original files preserved for explicit recovery: $results"
        }
    }
}
