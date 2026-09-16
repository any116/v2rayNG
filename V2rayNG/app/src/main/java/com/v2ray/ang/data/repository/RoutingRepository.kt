package com.v2ray.ang.data.repository

import android.app.Application
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.insertHeaderItem
import androidx.paging.map
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.RoutingEditData
import com.v2ray.ang.dto.RoutingRuleRow
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.dto.toRuleRows
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.extension.normalizeLike
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.DropdownOption
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

open class RoutingRepository @Inject constructor(
    private val app: Application,
    private val profileDao: ProfileDao,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    // ----- Rule loading with id deduplication -----

    open suspend fun loadRulesets(): List<RulesetItem> = runIO(emptyList()) { repairedRulesets() }

    open suspend fun loadRuleRows(): List<RoutingRuleRow> = runIO(emptyList()) {
        repairedRulesets().toRuleRows()
    }

    /**
     * One IO pass for the whole edit screen so the ViewModel can publish a single state.
     *
     * Goes through [repairedRulesets] on purpose: [updateRule] re-assigns duplicated ids, an
     * editor holding a pre-repair id could later update the wrong rule or fail to find it.
     * Throws on storage failure - the caller must distinguish "not found" from "cannot read".
     */
    open suspend fun loadEditData(ruleId: String): RoutingEditData = withIO {
        RoutingEditData(
            ruleset = if (ruleId.isEmpty()) null else repairedRulesets().find { it.id == ruleId },
            canUseProcess = SettingsManager.canUseProcessRouting(),
        )
    }

    open fun outboundTagPager(query: String): Flow<PagingData<DropdownOption>> = Pager(
        config = PagingConfig(
            pageSize = 40,
            initialLoadSize = 80,
            prefetchDistance = 20,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { profileDao.pageRemarks(OUTBOUND_EXCLUDED, query.trim().normalizeLike()) }
    ).flow.map { data ->
        var out = data
            .filter { it.remarks !in AppConfig.BUILTIN_OUTBOUND_TAGS }
            .map { DropdownOption(it.remarks, DropdownOption.Source.PROFILE) }
        AppConfig.BUILTIN_OUTBOUND_TAGS
            .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
            .reversed()
            .forEach { tag ->
                out = out.insertHeaderItem(item = DropdownOption(tag, DropdownOption.Source.BUILTIN))
            }
        out
    }

    // ----- Insert / update / remove by id (atomic) -----

    open suspend fun insertRule(item: RulesetItem): String = runIO("") {
        val list = repairedRulesets().toMutableList()
        if (item.id.isEmpty()) item.id = UUID.randomUUID().toString()
        list.add(0, item)
        withContext(NonCancellable) { MmkvManager.encodeRoutingRulesets(ArrayList(list)) }
        item.id
    }

    open suspend fun updateRule(item: RulesetItem): Boolean = runIO(false) {
        val list = repairedRulesets().toMutableList()
        val index = list.indexOfFirst { it.id == item.id }
        if (index < 0) return@runIO false
        list[index] = item
        withContext(NonCancellable) { MmkvManager.encodeRoutingRulesets(ArrayList(list)) }
        true
    }

    open suspend fun removeRule(ruleId: String): Boolean = runIO(false) {
        val list = repairedRulesets().toMutableList()
        if (!list.removeAll { it.id == ruleId }) return@runIO false
        withContext(NonCancellable) { MmkvManager.encodeRoutingRulesets(ArrayList(list)) }
        true
    }

    open suspend fun saveOrder(list: List<RulesetItem>) = runIO(Unit) {
        withContext(NonCancellable) { MmkvManager.encodeRoutingRulesets(ArrayList(list)) }
    }

    // ----- Domain strategy -----

    open suspend fun getDomainStrategy(): String = runIO("") {
        MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY).orEmpty()
    }

    open suspend fun setDomainStrategy(value: String) = runIO(Unit) {
        withContext(NonCancellable) {
            MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, value)
        }
    }

    // ----- Preset / import / export -----

    open suspend fun importPresets(type: RoutingType): Boolean = runIO(false) {
        SettingsManager.resetRoutingRulesetsFromPresets(app, type)
    }

    open suspend fun importRulesets(text: String?): Boolean = runIO(false) {
        if (text.isNullOrBlank()) return@runIO false
        SettingsManager.resetRoutingRulesets(text)
    }

    open suspend fun readClipboard(): String = runIO("") { Utils.getClipboard(app) }

    open suspend fun exportToClipboard(): Boolean = runIO(false) {
        val json = MmkvManager.decodeRoutingRulesets()
            ?.takeIf { it.isNotEmpty() }
            ?.let(JsonUtil::toJson)
            ?: return@runIO false
        Utils.setClipboard(app, json)
        true
    }

    // ----- Internals -----

    /** Reads the rulesets, giving every item a unique id */
    private suspend fun repairedRulesets(): List<RulesetItem> {
        val list = MmkvManager.decodeRoutingRulesets()?.toMutableList() ?: mutableListOf()
        var patched = false
        val seen = HashSet<String>(list.size)
        list.forEach { item ->
            if (item.id.isEmpty() || !seen.add(item.id)) {
                item.id = UUID.randomUUID().toString()
                seen.add(item.id)
                patched = true
            }
        }
        if (patched) {
            withContext(NonCancellable) { MmkvManager.encodeRoutingRulesets(ArrayList(list)) }
        }
        return list
    }

    private companion object {
        val OUTBOUND_EXCLUDED: List<Int> = listOf(EConfigType.CUSTOM.value)
    }
}
