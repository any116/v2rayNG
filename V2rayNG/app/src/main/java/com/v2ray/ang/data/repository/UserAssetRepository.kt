package com.v2ray.ang.data.repository

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.AssetDao
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Domain model of one asset entry. It lives in the data layer on purpose: the repository used to
 * import the UI contract type, which inverted the dependency direction of the whole feature.
 */
data class AssetFile(
    val guid: String,
    val remarks: String,
    val url: String,
    val locked: Boolean,
    val properties: String
) {
    val isLocalFile: Boolean get() = url == URL_LOCAL_FILE

    companion object {
        /** Marker url used for assets imported from a local file. */
        const val URL_LOCAL_FILE = "file"
    }
}

/** Result of importing a local file, expressed without UI strings. */
enum class AssetImportResult { SUCCESS, DUPLICATE, FAILURE }

/**
 * Data layer of the user-asset feature: the assets table, the external asset directory and
 * geo-file downloads. Every entry point is main-safe.
 */
open class UserAssetRepository @Inject constructor(
    private val app: Application,
    private val assetDao: AssetDao,
    private val settings: SettingsStore,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    private val extDir: File get() = File(Utils.userAssetPath(app))

    private val builtInGeoFiles = listOf(
        AppConfig.GEOSITE_DAT,
        AppConfig.GEOIP_DAT,
        AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT
    )

    /**
     * Bumped for changes Room cannot see: the geo source preference and the asset files on disk
     * (size / timestamp columns are read from the filesystem, not from the table).
     */
    private val revision = MutableStateFlow(0)

    // ---- read ----

    open fun observeAssets(): Flow<List<AssetFile>> =
        combine(assetDao.observeAll(), revision) { saved, _ ->
            composeAssets(saved, geoSource())
        }.flowIO()

    open fun geoSources(): List<String> = AppConfig.GEO_FILES_SOURCES.toList()

    /** Snapshot read, stays synchronous. */
    open fun geoSource(): String = settings.string(AppConfig.PREF_GEO_FILES_SOURCES)
        ?: AppConfig.GEO_FILES_SOURCES.first()

    open suspend fun setGeoSource(value: String) = withIO {
        withContext(NonCancellable) {
            settings.putString(AppConfig.PREF_GEO_FILES_SOURCES, value)
        }
        refresh()
    }

    open fun refresh() {
        revision.value += 1
    }

    /**
     * Built-in geo files are merged in front of the stored ones and deliberately never inserted:
     * they are not user data, and keeping them out of the table also keeps them out of backups.
     * Their synthetic guid is stable so LazyColumn keys stay valid across emissions.
     */
    private fun composeAssets(saved: List<AssetUrlItem>, source: String): List<AssetFile> {
        val builtIn = builtInGeoFiles
            .filter { name -> saved.none { it.remarks == name } }
            .map { name ->
                AssetUrlItem(
                    guid = "$BUILT_IN_PREFIX$name",
                    remarks = name,
                    url = String.format(AppConfig.GITHUB_DOWNLOAD_URL, source).concatUrl(name),
                    locked = true
                )
            }
        val files = extDir.listFiles()?.associateBy { it.name }.orEmpty()

        return (builtIn + saved).map { item ->
            // geoip-only-cn-private.dat always comes from its own repository.
            val url = if (item.remarks == AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT) {
                AppConfig.GEOIP_ONLY_CN_PRIVATE_URL
            } else {
                item.url
            }
            AssetFile(
                guid = item.guid,
                remarks = item.remarks,
                url = url,
                locked = item.locked == true,
                properties = formatProperties(files[item.remarks])
            )
        }
    }

    /** Formats the size/date line, or falls back to the localized "not found" message. */
    private fun formatProperties(file: File?): String = if (file != null) {
        "${file.length().toTrafficString()}    ${Utils.formatTimestamp(file.lastModified())}"
    } else {
        app.getString(R.string.msg_file_not_found)
    }

    // ---- write ----

    /** Copies the picked document into the asset directory and registers it. */
    open suspend fun importFile(uri: Uri): AssetImportResult = withIO {
        val name = cursorName(uri) ?: uri.toString()
        if (assetDao.findByRemarks(name) != null) return@withIO AssetImportResult.DUPLICATE

        val assetId = Utils.getUuid()
        val result = runCatching {
            withContext(NonCancellable) {
                assetDao.upsert(
                    AssetUrlItem(
                        guid = assetId,
                        remarks = name,
                        url = AssetFile.URL_LOCAL_FILE
                    )
                )
                app.contentResolver.openInputStream(uri).use { input ->
                    File(extDir, name).outputStream().use { output -> input?.copyTo(output) }
                }
            }
            AssetImportResult.SUCCESS
        }.getOrElse { e ->
            LogUtil.e(AppConfig.TAG, "Failed to import asset file", e)
            withContext(NonCancellable) { assetDao.delete(assetId) }
            AssetImportResult.FAILURE
        }
        refresh()
        result
    }

    /**
     * List-screen delete: drops the file from disk *and* the row, then restores the bundled
     * defaults, mirroring the legacy behaviour. Built-in rows are not in the table, so the DAO
     * call is a no-op for them and only the file is dropped.
     */
    open suspend fun removeAssetWithFile(guid: String, remarks: String) {
        withIO {
            withContext(NonCancellable) {
                extDir.listFiles()?.firstOrNull { it.name == remarks }?.delete()
                assetDao.delete(guid)
                SettingsManager.initAssets(app, app.assets)
            }
            refresh()
        }
    }

    /** Editor delete: forgets the source only; an already downloaded file stays usable. */
    open suspend fun removeAssetUrl(assetId: String) {
        withIO {
            withContext(NonCancellable) { assetDao.delete(assetId) }
            refresh()
        }
    }

    // ---- download ----

    /**
     * Downloads every listed asset, one at a time so the job stays cancellable and can
     * report progress.
     *
     * @return the number of successfully downloaded files.
     */
    open suspend fun downloadAll(onProgress: suspend (done: Int, total: Int) -> Unit): Int = withIO {
        val items = composeAssets(assetDao.all(), geoSource())
        val httpPort = SettingsManager.getHttpPort()
        val username = SettingsManager.getSocksUsername()
        val password = SettingsManager.getSocksPassword()
        var success = 0
        try {
            items.forEachIndexed { index, assetFile ->
                currentCoroutineContext().ensureActive()
                onProgress(index + 1, items.size)
                val ports = if (httpPort == 0) listOf(0) else listOf(httpPort, 0)
                if (ports.any { port -> download(assetFile, port, username, password) }) {
                    withContext(NonCancellable) {
                        assetDao.touch(assetFile.guid, System.currentTimeMillis())
                    }
                    success++
                }
            }
        } finally {
            // Files changed on disk even when the job was cancelled half way.
            refresh()
        }
        success
    }

    private fun download(
        assetFile: AssetFile,
        httpPort: Int,
        proxyUsername: String?,
        proxyPassword: String?
    ): Boolean = try {
        val temp = File(extDir, "${assetFile.remarks}_temp")
        val target = File(extDir, assetFile.remarks)
        val request = UrlContentRequest(
            url = assetFile.url,
            timeout = 15000,
            httpPort = httpPort,
            proxyUsername = proxyUsername,
            proxyPassword = proxyPassword
        )
        if (HttpUtil.downloadToFile(request, temp)) {
            temp.renameTo(target)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to download geo file: ${assetFile.remarks}", e)
        false
    }

    // ---- editor support ----

    open suspend fun loadAsset(assetId: String): AssetUrlItem? = withIO { assetDao.find(assetId) }

    open suspend fun isRemarkDuplicated(remarks: String, assetId: String): Boolean = withIO {
        assetDao.findByRemarks(remarks)?.guid?.let { it != assetId } == true
    }

    /** Saves the asset; the stale file is dropped only when the remark actually changed. */
    open suspend fun saveAsset(assetId: String, remarks: String, url: String): String = withIO {
        val existing = assetDao.find(assetId)
        val id = existing?.guid ?: Utils.getUuid()
        withContext(NonCancellable) {
            if (existing != null && existing.remarks != remarks) {
                runCatching { extDir.resolve(existing.remarks).takeIf { it.exists() }?.delete() }
                    .onFailure { LogUtil.e(AppConfig.TAG, "Failed to delete stale asset file", it) }
            }
            assetDao.upsert(
                existing?.copy(remarks = remarks, url = url)
                    ?: AssetUrlItem(guid = id, remarks = remarks, url = url)
            )
        }
        refresh()
        id
    }

    private fun cursorName(uri: Uri): String? = try {
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to get cursor name", e)
        null
    }

    private companion object {
        const val BUILT_IN_PREFIX = "builtin:"
    }
}
