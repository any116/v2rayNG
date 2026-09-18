package com.v2ray.ang.data.repository

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.SubscriptionDao
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.SubEditData
import com.v2ray.ang.dto.SubUpdateOptions
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.normalizeLike
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.compose.DropdownOption
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

open class SubRepository @Inject constructor(
    private val app: Application,
    private val profileDao: ProfileDao,
    private val subscriptionDao: SubscriptionDao,
    private val settings: SettingsStore,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    // ---- Read ----

    open fun observeSubscriptions(): Flow<List<SubscriptionItem>> =
        subscriptionDao.observeAll().flowIO()

    open suspend fun loadSubscriptions(): List<SubscriptionItem> = withIO { subscriptionDao.all() }

    open suspend fun loadSubscription(subId: String): SubscriptionItem? =
        withIO { subscriptionDao.find(subId) }

    open suspend fun loadEdit(subId: String): SubEditData = withIO {
        SubEditData(
            item = if (subId.isEmpty()) null else subscriptionDao.find(subId),
            confirmRemove = settings.bool(AppConfig.PREF_CONFIRM_REMOVE, false),
        )
    }

    open fun confirmRemove(): Boolean = settings.bool(AppConfig.PREF_CONFIRM_REMOVE, false)

    open suspend fun loadUpdateOptions(): SubUpdateOptions = withIO {
        SubUpdateOptions(
            updateSubscription = settings.bool(AppConfig.PREF_UPDATE_SUBSCRIPTION, false),
            autoTestAfterUpdate = settings.bool(AppConfig.PREF_AUTO_TEST_AFTER_UPDATE_SUBSCRIPTION, false),
            autoRemoveInvalid = settings.bool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false),
            autoSortAfterTest = settings.bool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)
        )
    }

    /** Entry/exit proxy pickers: replaces SubEditData.profileOptions. */
    open fun profileRemarkPager(query: String): Flow<PagingData<DropdownOption>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { profileDao.pageRemarks(COMPLEX_EXCLUDED, query.trim().normalizeLike()) }
        ).flow.map { data ->
            data.map { DropdownOption(it.remarks, DropdownOption.Source.PROFILE) }
        }

    // ---- Write ----

    open suspend fun save(subId: String, item: SubscriptionItem) = withIO {
        val targetGuid = subId.ifEmpty { item.guid.ifEmpty { Utils.getUuid() } }
        val existing = subscriptionDao.find(targetGuid)
        if (existing == null) {
            subscriptionDao.insertAtEnd(item.copy(guid = targetGuid))
        } else {
            subscriptionDao.upsert(item.copy(guid = targetGuid, sortOrder = existing.sortOrder))
        }
        SubscriptionUpdater.syncOne(subId = targetGuid)
        SettingsChangeManager.makeSetupGroupTab()
    }

    open suspend fun updateItem(subId: String, item: SubscriptionItem) = withIO {
        val existing = subscriptionDao.find(subId)
        subscriptionDao.upsert(
            item.copy(guid = subId, sortOrder = existing?.sortOrder ?: item.sortOrder)
        )
    }

    open suspend fun remove(subId: String) = withIO {
        SubscriptionUpdater.cancelOne(subId = subId)
        subscriptionDao.removeWithDefault(subId, AppConfig.DEFAULT_SUBSCRIPTION_REMARKS)
        settings.poke(SettingsStore.KEY_SELECTED_SERVER, profileDao.selectedGuid())
        SettingsChangeManager.makeSetupGroupTab()
    }

    open suspend fun saveOrder(guids: List<String>) = withIO {
        subscriptionDao.saveOrder(guids)
        SettingsChangeManager.makeSetupGroupTab()
    }

    open suspend fun saveUpdateOptions(options: SubUpdateOptions) = withIO {
        settings.putBool(AppConfig.PREF_UPDATE_SUBSCRIPTION, options.updateSubscription)
        settings.putBool(AppConfig.PREF_AUTO_TEST_AFTER_UPDATE_SUBSCRIPTION, options.autoTestAfterUpdate)
        settings.putBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, options.autoRemoveInvalid)
        settings.putBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, options.autoSortAfterTest)
    }

    // ---- Update ----

    open suspend fun updateAll(
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): SubscriptionUpdateResult = withIO {
        val subs = subscriptionDao.all()
        var acc = SubscriptionUpdateResult()
        onProgress(0, subs.size)
        subs.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            acc += AngConfigManager.updateConfigViaSub(item)
            onProgress(index + 1, subs.size)
        }
        acc
    }

    open suspend fun updateInBackground(): Boolean = withIO {
        SettingsChangeManager.makeSetupGroupTab()
        val subIds = subscriptionDao.all()
            .filter { it.enabled && it.url.isNotEmpty() }
            .map { it.guid }
        if (subIds.isEmpty()) return@withIO false
        MessageHelper.sendMsg2SubscriptionService(
            app,
            SubscriptionUpdateMessage(AppConfig.MSG_SUB_UPDATE_START, false, subIds)
        )
        true
    }

    // ---- Share ----

    open suspend fun createQrCode(url: String): Bitmap? = withIO { QRCodeDecoder.createQRCode(url) }

    open suspend fun copyToClipboard(text: String): Boolean = runCatching {
        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(null, text))
    }.isSuccess

    private companion object {
        const val PAGE_SIZE = 40
        const val INITIAL_LOAD_SIZE = 80
        const val PREFETCH_DISTANCE = 20

        val COMPLEX_EXCLUDED: List<Int> =
            EConfigType.entries.filter { it.isComplexType() }.map { it.value }
    }
}
