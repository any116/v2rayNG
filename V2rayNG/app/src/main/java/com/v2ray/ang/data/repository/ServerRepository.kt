package com.v2ray.ang.data.repository

import android.app.Application
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.insertHeaderItem
import androidx.paging.map
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.SubscriptionDao
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.ServerEditData
import com.v2ray.ang.dto.SubscriptionOption
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.normalizeLike
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.CertificateFingerprintManager
import com.v2ray.ang.ui.compose.DropdownOption
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

open class ServerRepository @Inject constructor(
    private val app: Application,
    private val profileDao: ProfileDao,
    private val subscriptionDao: SubscriptionDao,
    private val settings: SettingsStore,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    // ---- Read ----

    open suspend fun loadEdit(guid: String, fallbackType: EConfigType): ServerEditData = withIO {
        val stored = if (guid.isEmpty()) null else profileDao.findByGuid(guid)
        if (guid.isNotEmpty() && stored == null) return@withIO ServerEditData(profile = null)

        val configType = stored?.configType ?: fallbackType
        val isSelected = guid.isNotEmpty() && guid == profileDao.selectedGuid()

        when (configType) {
            EConfigType.CUSTOM -> ServerEditData(
                profile = stored,
                isSelected = isSelected,
                rawContent = if (guid.isEmpty()) "" else profileDao.raw(guid).orEmpty(),
            )

            EConfigType.POLICYGROUP -> ServerEditData(
                profile = stored,
                isSelected = isSelected,
                subscriptions = buildSubscriptions(),
            )

            else -> ServerEditData(profile = stored, isSelected = isSelected)
        }
    }

    open suspend fun isSelectedServer(guid: String): Boolean = withIO {
        guid.isNotEmpty() && guid == profileDao.selectedGuid()
    }

    open suspend fun findProfileByRemarks(remarks: String): ProfileItem? = withIO {
        profileDao.findByRemarks(remarks)
    }

    // ---- Paged dropdown sources ----

    /** Replaces buildChainCandidates(): List<String>. */
    open fun chainCandidatePager(query: String): Flow<PagingData<DropdownOption>> =
        Pager(
            config = pagingConfig(),
            pagingSourceFactory = { profileDao.pageRemarks(CHAIN_EXCLUDED, query.trim().normalizeLike()) }
        ).flow.map { data ->
            data.map { DropdownOption(it.remarks, DropdownOption.Source.PROFILE) }
        }

    /** Replaces buildFallbackTags(): List<String>. Builtin tags are not stored, so they go in as headers. */
    open fun fallbackTagPager(query: String): Flow<PagingData<DropdownOption>> =
        Pager(
            config = pagingConfig(),
            pagingSourceFactory = { profileDao.pageRemarks(FALLBACK_EXCLUDED, query.trim().normalizeLike()) }
        ).flow.map { data ->
            var out = data.map { DropdownOption(it.remarks, DropdownOption.Source.PROFILE) }
            AppConfig.BUILTIN_OUTBOUND_TAGS
                .filter { it != AppConfig.TAG_PROXY }
                .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
                .reversed()
                .forEach { tag ->
                    out = out.insertHeaderItem(item = DropdownOption(tag, DropdownOption.Source.BUILTIN))
                }
            out
        }

    // ---- Write ----

    /**
     * Replaces MmkvManager.encodeServerConfig. New rows go to the head of their group, the
     * selection is claimed when nothing is selected yet, and dedupeKey is invalidated so the
     * lazy backfill recomputes it.
     */
    open suspend fun saveProfile(guid: String, profile: ProfileItem): String = withIO {
        val targetGuid = guid.ifEmpty { profile.guid.ifEmpty { Utils.getUuid() } }
        val existing = profileDao.findByGuid(targetGuid)
        val subId = existing?.subscriptionId?.takeIf { it.isNotEmpty() }
            ?: profile.subscriptionId.ifEmpty { AppConfig.DEFAULT_SUBSCRIPTION_ID }
        if (subscriptionDao.find(subId) == null) {
            subscriptionDao.ensureDefault(AppConfig.DEFAULT_SUBSCRIPTION_REMARKS)
        }

        profileDao.upsert(
            profile.copy(
                guid = targetGuid,
                subscriptionId = subId,
                sortOrder = existing?.sortOrder
                    ?: (profileDao.minSortOrder(subId) - ProfileItem.SORT_STEP),
                dedupeKey = "",
            )
        )
        if (profileDao.selectedGuid().isNullOrEmpty()) {
            profileDao.writeSelectedGuid(targetGuid)
            settings.poke(SettingsStore.KEY_SELECTED_SERVER, targetGuid)
        }
        targetGuid
    }

    open suspend fun saveRawConfig(guid: String, content: String) = withIO {
        profileDao.putRaw(ProfileRaw(guid, content))
    }

    open suspend fun removeProfile(guid: String) = withIO {
        profileDao.deleteProfiles(listOf(guid))
        settings.poke(SettingsStore.KEY_SELECTED_SERVER, profileDao.selectedGuid())
    }

    // ---- Derived text ----

    open suspend fun generateDescription(profile: ProfileItem): String = withIO {
        AngConfigManager.generateDescription(profile)
    }

    open suspend fun parseCustomConfig(content: String): ProfileItem? = withIO {
        CustomFmt.parse(content)
    }

    open suspend fun fetchCertSha256(profile: ProfileItem): String? = withIO {
        CertificateFingerprintManager.fetchForManualFill(profile)
    }

    open suspend fun buildPolicyGroupDescription(
        typeIndex: Int,
        subId: String,
        filter: String,
    ): String = withIO {
        val typeName = app.resources.getStringArray(R.array.policy_group_type)
            .getOrNull(typeIndex).orEmpty()
        val subName = if (subId.isEmpty()) {
            app.getString(R.string.filter_config_all)
        } else {
            subscriptionDao.find(subId)?.remarks?.ifBlank { subId } ?: subId
        }
        "$typeName - $subName - $filter"
    }

    // ---- Internals ----

    private suspend fun buildSubscriptions(): List<SubscriptionOption> = buildList {
        add(SubscriptionOption(id = "", name = ""))
        subscriptionDao.all().forEach { sub ->
            add(SubscriptionOption(id = sub.guid, name = sub.remarks.ifBlank { sub.guid }))
        }
    }

    private companion object {
        val CHAIN_EXCLUDED: List<Int> =
            EConfigType.entries.filter { it.isComplexType() }.map { it.value }

        val FALLBACK_EXCLUDED: List<Int> =
            listOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP).map { it.value }

        const val PAGE_SIZE = 40
        const val INITIAL_LOAD_SIZE = 80
        const val PREFETCH_DISTANCE = 20

        fun pagingConfig() = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            prefetchDistance = PREFETCH_DISTANCE,
            enablePlaceholders = false,
        )
    }
}
