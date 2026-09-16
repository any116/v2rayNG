package com.v2ray.ang.data.repository

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.data.AppDatabase
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.entities.WebDavConfig
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.data.legacy.MmkvLegacyReader
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.helper.MessageHelper
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
     * Produces a self-consistent copy of the database with VACUUM INTO, then zips it.
     */
    open suspend fun packToCache(): File? = runIO(null) {
        val dir = prepareWorkDir()
        val folder = "${appName}_${timestamp()}"
        val dumpDir = File(dir, folder)
        val zip = File(dir, "$folder$ZIP_SUFFIX")
        try {
            dumpDir.mkdirs()
            val target = File(dumpDir, AppDatabase.NAME)
            target.delete()  // VACUUM INTO refuses to overwrite

            db.useWriterConnection<Unit> { conn ->
                conn.usePrepared("VACUUM INTO ?") { st ->
                    st.bindText(1, target.absolutePath)
                    st.step()
                }
            }

            if (!target.isFile || target.length() == 0L) {
                LogUtil.w(AppConfig.TAG, "Backup aborted: VACUUM INTO produced no file")
                zip.delete()
                return@runIO null
            }

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

            stopSiblingProcesses()

            when {
                incoming.isFile -> {
                    db.close()
                    clearSidecars(dbPath)
                    incoming.copyTo(dbPath, overwrite = true)
                }

                legacyDir != null -> {
                    val snapshot = MmkvLegacyReader(
                        context = app,
                        rootDir = legacyDir.absolutePath,
                    ).readAll()
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
     * Asks the core service to shut down and then kills every sibling process owned by the same
     * UID (:daemon, :bg, :tasks).
     */
    private fun stopSiblingProcesses() {
        LauncherManager.stopService(app)
        MessageHelper.sendMsg2Service(app, AppConfig.MSG_STATE_STOP, "")

        runCatching { Thread.sleep(SERVICE_STOP_GRACE_MS) }

        val am = app.getSystemService(ActivityManager::class.java) ?: return
        val selfPid = android.os.Process.myPid()
        val selfUid = android.os.Process.myUid()
        runCatching {
            am.runningAppProcesses
                ?.filter { it.uid == selfUid && it.pid != selfPid }
                ?.forEach { android.os.Process.killProcess(it.pid) }
        }.onFailure { LogUtil.w(AppConfig.TAG, "Failed to kill sibling processes", it) }
    }

    /**
     * A restored legacy snapshot has to survive the process restart, it consumed by
     * the database create callback in whichever process opens the file next.
     */
    private fun stagePendingSnapshot(snapshot: LegacySnapshot) {
        pendingSnapshotFile(app).writeText(JsonUtil.toJson(snapshot))
    }

    private fun clearSidecars(dbPath: File) {
        File("${dbPath.path}-wal").delete()
        File("${dbPath.path}-shm").delete()
    }

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
        android.os.Process.killProcess(android.os.Process.myPid())
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
        private const val SERVICE_STOP_GRACE_MS = 600L
        private const val PENDING_SNAPSHOT = "pending_legacy_snapshot.json"

        fun pendingSnapshotFile(app: Application): File = File(app.filesDir, PENDING_SNAPSHOT)
    }
}
