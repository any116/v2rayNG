package com.v2ray.ang.data

import android.app.Application
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * Runs PRAGMA quick_check before Room opens the database. A corrupt database still builds
 * successfully through Room; every query then throws, the UI renders an empty list, and the
 * user has no way to tell what happened.
 *
 * Must be called before this process resolves AppDatabase for the first time.
 *
 * The check only reports; it never moves, renames or deletes anything. "The check could not
 * run" (lock contention, I/O error) is not "the database is corrupt", and quarantining a live
 * file while other processes still hold Room connections — or deleting it when the rename
 * fails — can destroy healthy user data. A failing check
 * therefore aborts the storage bootstrap and the files stay on disk for an explicit,
 * offline recovery.
 *
 * Uses quick_check rather than integrity_check on purpose: every process runs this on every
 * cold start, including the :daemon brought up by Always-on VPN at boot, and the full
 * integrity_check scans every index as well as every table. quick_check verifies the same
 * physical page structure (the failure mode that makes Room throw on every query) at a
 * fraction of the cost, which keeps the VPN start path short.
 *
 * Runs once per process — the caller is invoked once from Application.onCreate's bootstrap
 * coroutine, so the cost is paid once at cold start.
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

                // quick_check only emits more than one row when it found something to report,
                // so a single "ok" is the healthy result.
                conn.prepare("PRAGMA quick_check").use { st ->
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
            "Database quick_check failed; original files preserved for explicit recovery: $results"
        }
    }
}
