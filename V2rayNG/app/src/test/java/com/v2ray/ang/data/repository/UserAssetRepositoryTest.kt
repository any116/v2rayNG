package com.v2ray.ang.data.repository

import android.app.Application
import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.dao.AssetDao
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.dto.UrlContentRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UserAssetRepositoryTest {

    @TempDir
    lateinit var extDir: File

    private val dispatcher = StandardTestDispatcher()
    private val app = mock<Application>()
    private val assetDao = mock<AssetDao>()
    private val settings = mock<SettingsStore>()
    private lateinit var logs: MockedStatic<Log>
    private val assets = listOf(
        AppConfig.GEOSITE_DAT,
        AppConfig.GEOIP_DAT,
        AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT
    ).mapIndexed { index, name ->
        AssetUrlItem(guid = "asset-$index", remarks = name, url = "https://example.invalid/$name", lastUpdated = 123L)
    }

    @BeforeEach
    fun setUp() {
        logs = mockStatic(Log::class.java)
        whenever(app.getDir(AppConfig.DIR_ASSETS, Context.MODE_PRIVATE)).thenReturn(extDir)
        whenever(app.getString(R.string.msg_file_not_found)).thenReturn("missing")
    }

    @AfterEach
    fun tearDown() {
        logs.close()
    }

    @Test
    fun successfulReplacementsUpdateExistingFilesAndRemoveTemporaryFiles() = runTest(dispatcher) {
        whenever(assetDao.all()).thenReturn(assets)
        assets.forEach { File(extDir, it.remarks).writeText("old") }
        val repo = repository { _, temp ->
            temp.writeText("new")
            true
        }

        val success = repo.downloadAll { _, _ -> }

        assertEquals(assets.size, success)
        verify(assetDao).all()
        assets.forEach { asset ->
            val target = File(extDir, asset.remarks)
            assertTrue(target.isFile)
            assertEquals("new", target.readText())
            assertFalse(File(extDir, "${asset.remarks}_temp").exists())
            verify(assetDao).touch(eq(asset.guid), any())
        }
        verifyNoMoreInteractions(assetDao)
    }

    @Test
    fun missingDownloadedFilesLeaveExistingAssetsUntouchedAndAreNotCounted() = runTest(dispatcher) {
        whenever(assetDao.all()).thenReturn(assets)
        assets.forEach { File(extDir, it.remarks).writeText("old") }
        val attempts = mutableListOf<String>()
        val repo = repository { _, temp ->
            attempts += temp.name
            temp.writeText("new")
            // Simulate the downloaded file disappearing before renameTo, forcing a real rename failure.
            assertTrue(temp.delete())
            true
        }

        val success = repo.downloadAll { _, _ -> }

        assertEquals(0, success)
        assertEquals(assets.map { "${it.remarks}_temp" }, attempts)
        assets.forEach { asset ->
            val target = File(extDir, asset.remarks)
            assertTrue(target.isFile)
            assertEquals("old", target.readText())
            assertFalse(File(extDir, "${asset.remarks}_temp").exists())
        }
        verify(assetDao).all()
        verify(assetDao, never()).touch(any(), any())
        verifyNoMoreInteractions(assetDao)
    }

    @Test
    fun failedTransfersWithoutTemporaryFilesAreNotCountedOrTouched() = runTest(dispatcher) {
        whenever(assetDao.all()).thenReturn(assets)
        assets.forEach { File(extDir, it.remarks).writeText("old") }
        val attempts = mutableListOf<String>()
        val repo = repository { _, temp ->
            attempts += temp.name
            assertFalse(temp.exists())
            false
        }

        val success = repo.downloadAll { _, _ -> }

        assertEquals(0, success)
        assertEquals(assets.map { "${it.remarks}_temp" }, attempts)
        assets.forEach { asset ->
            assertEquals("old", File(extDir, asset.remarks).readText())
            assertFalse(File(extDir, "${asset.remarks}_temp").exists())
        }
        verify(assetDao).all()
        verify(assetDao, never()).touch(any(), any())
        verifyNoMoreInteractions(assetDao)
    }

    @Test
    fun failedReplacementsAreNotCountedOrTouched() = runTest(dispatcher) {
        whenever(assetDao.all()).thenReturn(assets)
        assets.forEach { asset ->
            val target = File(extDir, asset.remarks).apply { mkdirs() }
            File(target, "keep").writeText("old")
        }
        val attempts = mutableListOf<String>()
        val repo = repository { _, temp ->
            attempts += temp.name
            temp.writeText("new")
            true
        }
        val progress = mutableListOf<Pair<Int, Int>>()

        val success = repo.downloadAll { done, total -> progress += done to total }

        assertEquals(0, success)
        assertEquals(assets.map { "${it.remarks}_temp" }, attempts)
        assertEquals((1..assets.size).map { it to assets.size }, progress)
        assets.forEach { asset ->
            assertEquals("old", File(File(extDir, asset.remarks), "keep").readText())
            assertFalse(File(extDir, "${asset.remarks}_temp").exists())
        }
        verify(assetDao).all()
        verify(assetDao, never()).touch(any(), any())
        verifyNoMoreInteractions(assetDao)
    }

    @Test
    fun onlySuccessfullyReplacedAssetsAreCountedAndTouched() = runTest(dispatcher) {
        whenever(assetDao.all()).thenReturn(assets)
        val blocked = File(extDir, assets[0].remarks).apply { mkdirs() }
        File(blocked, "keep").writeText("old")
        val unchanged = File(extDir, assets[2].remarks).apply { writeText("old") }
        val attempts = mutableListOf<String>()
        val repo = repository { _, temp ->
            attempts += temp.name
            val transferSucceeded = temp.name != "${assets[2].remarks}_temp"
            temp.writeText(if (transferSucceeded) "new" else "partial")
            transferSucceeded
        }

        val success = repo.downloadAll { _, _ -> }

        assertEquals(1, success)
        assertEquals(assets.map { "${it.remarks}_temp" }, attempts)
        assertEquals("old", File(blocked, "keep").readText())
        assertEquals("new", File(extDir, assets[1].remarks).readText())
        assertEquals("old", unchanged.readText())
        assets.forEach { assertFalse(File(extDir, "${it.remarks}_temp").exists()) }
        verify(assetDao).all()
        verify(assetDao).touch(eq(assets[1].guid), any())
        verify(assetDao, never()).touch(eq(assets[0].guid), any())
        verify(assetDao, never()).touch(eq(assets[2].guid), any())
        verifyNoMoreInteractions(assetDao)
    }

    @Test
    fun directRetriesAfterFailedTransfersCountAndTouchEachAssetOnlyOnce() = runTest(dispatcher) {
        whenever(assetDao.all()).thenReturn(assets)
        val proxyPort = 1080
        val attempts = mutableListOf<Pair<String, Int>>()
        val repo = repository(httpPort = proxyPort) { request, temp ->
            attempts += temp.name to request.httpPort
            assertEquals("test-user", request.proxyUsername)
            assertEquals("test-password", request.proxyPassword)
            val direct = request.httpPort == 0
            if (direct) assertFalse(temp.exists())
            temp.writeText(if (direct) "new" else "partial")
            direct
        }

        val success = repo.downloadAll { _, _ -> }

        assertEquals(assets.size, success)
        assertEquals(
            assets.flatMap { listOf("${it.remarks}_temp" to proxyPort, "${it.remarks}_temp" to 0) },
            attempts
        )
        verify(assetDao).all()
        assets.forEach { asset ->
            assertEquals("new", File(extDir, asset.remarks).readText())
            assertFalse(File(extDir, "${asset.remarks}_temp").exists())
            verify(assetDao).touch(eq(asset.guid), any())
        }
        verifyNoMoreInteractions(assetDao)
    }

    private fun repository(
        httpPort: Int = 0,
        transfer: (UrlContentRequest, File) -> Boolean
    ): UserAssetRepository = object : UserAssetRepository(app, assetDao, settings, dispatcher) {
        protected override fun downloadRequest(): UrlContentRequest = UrlContentRequest(
            url = null,
            httpPort = httpPort,
            proxyUsername = "test-user",
            proxyPassword = "test-password"
        )

        protected override fun downloadToFile(request: UrlContentRequest, temp: File): Boolean = transfer(request, temp)
    }
}
