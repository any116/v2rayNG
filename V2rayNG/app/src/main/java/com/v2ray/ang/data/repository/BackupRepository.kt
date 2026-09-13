package com.v2ray.ang.data.repository

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.room3.useWriterConnection
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.AppDatabase
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.entities.WebDavConfig
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.data.legacy.MmkvLegacyReader
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

open class BackupRepository @Inject constructor(
    private val app: Application,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    @IoDispatcher io: CoroutineDispatcher,
) : BaseRepository(io) {

    private val workDir: File get() = File(app.cacheDir, WORK_DIR_NAME)
    private val appName: String get() = app.getString(R.string.app_name)
    private val sequence = AtomicLong()

    // ---- WebDAV settings ----

    open suspend fun loadWebDav(): WebDavConfig? = withIO {
        settings.string(SettingsStore.KEY_WEBDAV_CONFIG)
            ?.let { JsonUtil.fromJsonSafe(it, WebDavConfig::class.java) }
    }

    open suspend fun saveWebDav(config: WebDavConfig) = withIO {
        settings.putString(SettingsStore.KEY_WEBDAV_CONFIG, JsonUtil.toJson(config))
    }

    // ---- naming / scratch ----

    open suspend fun defaultFileName(): String = withIO { "${appName}_${timestamp()}$ZIP_SUFFIX" }

    open suspend fun cleanWorkDir() = withIO {
        workDir.deleteRecursively()
        Unit
    }

    open suspend fun discard(archive: File) = withIO {
        archive.delete()
        Unit
    }

    // ---- pack ----

    /**
     * Checkpoints the WAL into the main file, then copies the main file only. -wal and -shm are
     * runtime artefacts; shipping them makes a cross-device restore worse, not better.
     */
    open suspend fun packToCache(): File? = runIO(null) {
        val dir = prepareWorkDir()
        val folder = "${appName}_${timestamp()}"
        val dumpDir = File(dir, folder)
        val zip = File(dir, "$folder$ZIP_SUFFIX")
        try {
            dumpDir.mkdirs()
            checkpoint()

            val source = app.getDatabasePath(AppDatabase.NAME)
            if (!source.isFile) {
                LogUtil.w(AppConfig.TAG, "Backup aborted: no database file to dump")
                zip.delete()
                return@runIO null
            }
            source.copyTo(File(dumpDir, AppDatabase.NAME), overwrite = true)

            if (!ZipUtil.zipFromFolder(dumpDir.absolutePath, zip.absolutePath)) {
                LogUtil.w(AppConfig.TAG, "Backup aborted: zipping ${dumpDir.name} failed")
                zip.delete()
                null
            } else {
                zip
            }
        } finally {
            dumpDir.deleteRecursively()
        }
    }

    private suspend fun checkpoint() = db.useWriterConnection { connection ->
        connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() }
    }

    open suspend fun exportTo(zip: File, target: Uri): Boolean = runIO(false) {
        val output = app.contentResolver.openOutputStream(target) ?: return@runIO false
        output.use { sink -> zip.inputStream().use { it.copyTo(sink) } }
        true
    }

    open suspend fun importToCache(source: Uri): File? = runIO(null) {
        val target = File(prepareWorkDir(), "$IMPORT_PREFIX${unique()}$ZIP_SUFFIX")
        var ok = false
        try {
            app.contentResolver.openInputStream(source)?.use { stream ->
                target.outputStream().use { stream.copyTo(it) }
                ok = true
            }
        } finally {
            if (!ok) target.delete()
        }
        target.takeIf { ok }
    }

    // ---- restore ----

    /**
     * Two archive shapes are supported:
     *   - new: contains v2rayng.db, which simply replaces the current file
     *   - old: contains only an MMKV directory, which is read into a LegacySnapshot, staged, and
     *     replayed through the SAME LegacyImporter path as the first-time import
     *
     * The process always restarts afterwards. A closed RoomDatabase cannot be reused (Room 3.0.2
     * and later throw IllegalStateException on use after close), so asking the user to restart
     * manually guarantees a crash whenever they decline.
     */
    open suspend fun restore(zip: File): Boolean = runIO(false) {
        val target = File(prepareWorkDir(), "$UNPACK_PREFIX${unique()}")
        try {
            if (!ZipUtil.unzipToFolder(zip, target.absolutePath)) {
                LogUtil.w(AppConfig.TAG, "Restore aborted: ${zip.name} is not a readable archive")
                return@runIO false
            }

            val incoming = File(target, AppDatabase.NAME)
            val legacyDir = findLegacyMmkvDir(target)
            val dbPath = app.getDatabasePath(AppDatabase.NAME)

            when {
                incoming.isFile -> {
                    db.close()
                    clearSidecars(dbPath)
                    incoming.copyTo(dbPath, overwrite = true)
                }

                legacyDir != null -> {
                    val snapshot = MmkvLegacyReader(rootDir = legacyDir.absolutePath).readAll()
                    if (snapshot.isEmpty) {
                        LogUtil.w(AppConfig.TAG, "Restore aborted: legacy archive carried no data")
                        return@runIO false
                    }
                    stagePendingSnapshot(snapshot)
                    db.close()
                    clearSidecars(dbPath)
                    dbPath.delete()
                }

                else -> {
                    LogUtil.w(AppConfig.TAG, "Restore aborted: archive carried neither a database nor an MMKV store")
                    return@runIO false
                }
            }

            scheduleSelfRestart()
            true
        } finally {
            target.deleteRecursively()
        }
    }

    /**
     * A restored legacy snapshot has to survive the process restart, because it is consumed by
     * the database create callback in whichever process opens the file next. It is serialised
     * into files/ rather than cache/ so the platform cannot evict it in between.
     */
    private fun stagePendingSnapshot(snapshot: LegacySnapshot) {
        pendingSnapshotFile(app).writeText(JsonUtil.toJson(snapshot))
    }

    private fun clearSidecars(dbPath: File) {
        File("${dbPath.path}-wal").delete()
        File("${dbPath.path}-shm").delete()
    }

    /** MMKV stores live in a directory containing a MAIN file; the archive layout may nest it. */
    private fun findLegacyMmkvDir(root: File): File? {
        if (File(root, LEGACY_PROBE_FILE).isFile) return root
        return root.walkTopDown().maxDepth(3)
            .firstOrNull { it.isDirectory && File(it, LEGACY_PROBE_FILE).isFile }
    }

    private fun scheduleSelfRestart() {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            app, RESTART_REQUEST_CODE, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = app.getSystemService(AlarmManager::class.java)
        alarm?.set(
            AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
            pending,
        )
        Runtime.getRuntime().exit(0)
    }

    // ---- WebDAV transfer ----

    open suspend fun uploadBackup(config: WebDavConfig, zip: File): Boolean = runIO(false) {
        WebDavManager.init(config)
        WebDavManager.uploadFile(zip, AppConfig.WEBDAV_BACKUP_FILE_NAME)
    }

    open suspend fun downloadBackup(config: WebDavConfig): File? = runIO(null) {
        val target = File(prepareWorkDir(), "$DOWNLOAD_PREFIX${unique()}$ZIP_SUFFIX")
        var ok = false
        try {
            WebDavManager.init(config)
            ok = WebDavManager.downloadFile(AppConfig.WEBDAV_BACKUP_FILE_NAME, target)
        } finally {
            if (!ok) target.delete()
        }
        target.takeIf { ok }
    }

    // ---- cleanup ----

    open suspend fun cleanupProfiles(): Int = withIO { db.profileDao().cleanupOrphans() }

    // ---- helpers ----

    private fun prepareWorkDir(): File = workDir.apply { mkdirs() }

    /** Locale.US on purpose: a localised calendar would put non-ASCII digits into file names. */
    private fun timestamp(): String =
        SimpleDateFormat(STAMP_FORMAT, Locale.US).format(System.currentTimeMillis())

    private fun unique(): String = "${System.currentTimeMillis()}_${sequence.incrementAndGet()}"

    companion object {
        private const val WORK_DIR_NAME = "backup"
        private const val ZIP_SUFFIX = ".zip"
        private const val IMPORT_PREFIX = "restore_"
        private const val UNPACK_PREFIX = "unpack_"
        private const val DOWNLOAD_PREFIX = "webdav_"
        private const val STAMP_FORMAT = "yyyy-MM-dd-HH-mm-ss"
        private const val LEGACY_PROBE_FILE = "MAIN"
        private const val RESTART_REQUEST_CODE = 0x5265
        private const val RESTART_DELAY_MS = 400L
        private const val PENDING_SNAPSHOT = "pending_legacy_snapshot.json"

        fun pendingSnapshotFile(app: Application): File = File(app.filesDir, PENDING_SNAPSHOT)
    }
}
