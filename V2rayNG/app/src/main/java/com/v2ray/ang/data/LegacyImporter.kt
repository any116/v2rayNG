package com.v2ray.ang.data

import androidx.sqlite.SQLiteStatement
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.util.JsonUtil

/** A table a legacy import writes, together with its primary-key column. */
internal data class ImportedTable(val table: String, val column: String)

/**
 * Every table [LegacyImporter] writes. LegacyMigrationGate's verification pass walks this same
 * list; the plan test asserts that [LegacyImporter.ImportPlan.keysOf] knows every entry, so a
 * new table cannot be added here without a matching key source.
 */
internal val IMPORTED_TABLES = listOf(
    ImportedTable("profiles", "guid"),
    ImportedTable("profile_stats", "guid"),
    ImportedTable("profile_raw", "guid"),
    ImportedTable("subscriptions", "guid"),
    ImportedTable("assets", "guid"),
    ImportedTable("routing_rules", "id"),
    ImportedTable("settings", "key"),
)

/**
 * The only snapshot -> database transfer implementation. First-time import and old-archive
 * restore share this code path on purpose: two copies would mean two behaviours and one test.
 *
 * Inserts only. No parsing, no cleaning, no dedupe:
 *   - any cleaning would invalidate the importer's equality assertions, so a mismatch could no
 *     longer be attributed to either transfer or cleaning
 *   - dedupeKey is left empty and backfilled lazily by ProfileDao.backfillDedupeKeys, because a
 *     few thousand SHA-256 digests plus Gson serialisation inside the create transaction is a
 *     measurable ANR risk on the first process to open the database
 * Orphan cleanup belongs to ProfileDao.cleanupOrphans(), not here.
 *
 * The transfer is split in two phases on purpose: [plan] is a pure function that computes every
 * row and the distinct primary keys per table, [importInto] executes exactly that plan. The
 * gate verifies against the same plan, so "the keys we check" can never drift from "the keys we
 * write" — and a partially pre-populated database (for example, defaults seeded by an earlier
 * failed attempt) no longer makes the verification unwinnable.
 *
 * Called by LegacyMigrationGate inside an immediateTransaction on a writer connection.
 */
internal object LegacyImporter {

    /** One planned row per statement of [importInto], in execution order. */
    class ImportPlan(
        val subscriptions: List<SubscriptionRow>,
        val profiles: List<ProfileRow>,
        val profileStats: List<StatRow>,
        val profileRaws: List<RawRow>,
        val assets: List<AssetUrlItem>,
        val rules: List<RuleRow>,
        val settings: List<SettingRow>,
        private val keysByTable: Map<String, List<String>>,
    ) {
        /** Distinct primary keys this plan writes into [table]; empty for unknown tables. */
        fun keysOf(table: String): List<String> = keysByTable[table].orEmpty()
    }

    class SubscriptionRow(val guid: String, val sortOrder: Long, val item: SubscriptionItem)

    class ProfileRow(
        val guid: String,
        val subscriptionId: String,
        val sortOrder: Long,
        val profile: ProfileItem,
    )

    class StatRow(val guid: String, val delay: Long)

    class RawRow(val guid: String, val content: String)

    class RuleRow(val id: String, val sortOrder: Long, val rule: RulesetItem)

    class SettingRow(val key: String, val value: String?, val kind: String)

    /**
     * Pure computation of the whole transfer. Mirrors the historical decode order: SUB_IDS
     * first, then subscriptions outside it, then the synthesized default row, then profiles
     * group by group (DEFAULT_SUBSCRIPTION_ID leads when absent from SUB_IDS).
     */
    fun plan(snapshot: LegacySnapshot): ImportPlan {
        val sortStep = ProfileItem.SORT_STEP

        // 1) Subscriptions: the subOrder index becomes sortOrder.
        val subscriptions = mutableListOf<SubscriptionRow>()
        snapshot.subOrder.forEachIndexed { index, subId ->
            val item = snapshot.subscriptions[subId] ?: return@forEachIndexed
            subscriptions += SubscriptionRow(subId, (index + 1) * sortStep, item)
        }
        // Subscriptions present in SUB but absent from SUB_IDS still need a row.
        var tail = snapshot.subOrder.size
        snapshot.subscriptions.forEach { (subId, item) ->
            if (subId in snapshot.subOrder) return@forEach
            tail++
            subscriptions += SubscriptionRow(subId, tail * sortStep, item)
        }

        if (AppConfig.DEFAULT_SUBSCRIPTION_ID !in snapshot.subscriptions) {
            subscriptions += SubscriptionRow(
                AppConfig.DEFAULT_SUBSCRIPTION_ID,
                0L,
                SubscriptionItem(
                    guid = AppConfig.DEFAULT_SUBSCRIPTION_ID,
                    remarks = AppConfig.DEFAULT_SUBSCRIPTION_REMARKS,
                ),
            )
        }

        // 2) Profiles, group by group; the index inside a group becomes sortOrder.
        val profiles = mutableListOf<ProfileRow>()
        val stats = mutableListOf<StatRow>()
        val raws = mutableListOf<RawRow>()
        val groupOrder = buildList {
            if (AppConfig.DEFAULT_SUBSCRIPTION_ID !in snapshot.subOrder) {
                add(AppConfig.DEFAULT_SUBSCRIPTION_ID)
            }
            addAll(snapshot.subOrder)
            addAll(snapshot.groups.keys)
        }.distinct()

        groupOrder.forEach { subId ->
            snapshot.groups[subId]?.forEachIndexed { index, (guid, profile) ->
                profiles += ProfileRow(guid, subId, (index + 1) * sortStep, profile)
                snapshot.stats[guid]?.let { stats += StatRow(guid, it) }
                snapshot.raws[guid]?.let { raws += RawRow(guid, it) }
            }
        }

        // 3) Routing rules: the array index becomes sortOrder. An id-less legacy rule would
        //    collide on the primary key; give it a stable synthetic one.
        val rules = snapshot.rulesets.mapIndexed { index, rule ->
            val sortOrder = (index + 1) * sortStep
            RuleRow(rule.id.ifBlank { "legacy-$sortOrder" }, sortOrder, rule)
        }

        // 4) Scalar preferences, plus the dedupeKey algorithm version for later comparison.
        val settings = snapshot.settings.map { SettingRow(it.key, it.value, it.kind) } +
            SettingRow(
                SettingsStore.KEY_DEDUPE_ALGO_VERSION,
                ProfileItem.DEDUPE_ALGO_VERSION.toString(),
                SettingsStore.KIND_INT,
            )

        val keysByTable = mapOf(
            "profiles" to profiles.map { it.guid }.distinct(),
            "profile_stats" to stats.map { it.guid }.distinct(),
            "profile_raw" to raws.map { it.guid }.distinct(),
            "subscriptions" to subscriptions.map { it.guid }.distinct(),
            "assets" to snapshot.assets.map { it.guid }.distinct(),
            "routing_rules" to rules.map { it.id }.distinct(),
            "settings" to settings.map { it.key }.distinct(),
        )

        return ImportPlan(
            subscriptions = subscriptions,
            profiles = profiles,
            profileStats = stats,
            profileRaws = raws,
            assets = snapshot.assets.toList(),
            rules = rules,
            settings = settings,
            keysByTable = keysByTable,
        )
    }

    /** Executes [plan] through [exec]; each callback binds, the executor steps once. */
    suspend fun importInto(exec: SqlExec, plan: ImportPlan) {
        plan.subscriptions.forEach { insertSubscription(exec, it) }
        plan.profiles.forEach { insertProfile(exec, it) }
        plan.profileStats.forEach { insertStat(exec, it) }
        plan.profileRaws.forEach { insertRaw(exec, it) }
        plan.assets.forEach { insertAsset(exec, it) }
        plan.rules.forEach { insertRule(exec, it) }
        plan.settings.forEach { putSetting(exec, it) }
    }

    private suspend fun insertSubscription(
        exec: SqlExec,
        row: SubscriptionRow,
    ) = exec(
        """
        INSERT OR REPLACE INTO subscriptions
          (guid, sortOrder, remarks, url, enabled, addedTime, lastUpdated, autoUpdate,
           updateInterval, prevProfile, nextProfile, filter, allowInsecureUrl,
           userAgent, requestHeaders)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """
    ) {
        bindText(1, row.guid)
        bindLong(2, row.sortOrder)
        bindText(3, row.item.remarks)
        bindText(4, row.item.url)
        bindLong(5, row.item.enabled.toSqlite())
        bindLong(6, row.item.addedTime)
        bindLong(7, row.item.lastUpdated)
        bindLong(8, row.item.autoUpdate.toSqlite())
        bindLong(9, row.item.updateInterval)
        bindNullableText(10, row.item.prevProfile)
        bindNullableText(11, row.item.nextProfile)
        bindNullableText(12, row.item.filter)
        bindLong(13, row.item.allowInsecureUrl.toSqlite())
        bindNullableText(14, row.item.userAgent)
        bindNullableText(15, row.item.requestHeaders)
    }

    @Suppress("DEPRECATION")
    private suspend fun insertProfile(
        exec: SqlExec,
        row: ProfileRow,
    ) = exec(PROFILE_INSERT) {
        val p = row.profile
        bindText(1, row.guid)
        bindLong(2, row.sortOrder)
        bindText(3, "")                      // dedupeKey: lazily backfilled
        bindLong(4, p.configVersion.toLong())
        bindLong(5, p.configType.value.toLong())
        bindText(6, row.subscriptionId)
        bindLong(7, p.addedTime)
        bindText(8, p.remarks)
        bindNullableText(9, p.description)
        bindNullableText(10, p.server)
        bindNullableText(11, p.serverPort)
        bindNullableText(12, p.password)
        bindNullableText(13, p.method)
        bindNullableText(14, p.flow)
        bindNullableText(15, p.username)
        bindNullableText(16, p.network)
        bindNullableText(17, p.headerType)
        bindNullableText(18, p.host)
        bindNullableText(19, p.path)
        bindNullableText(20, p.seed)
        bindNullableLong(21, p.kcpMtu?.toLong())
        bindNullableLong(22, p.kcpTti?.toLong())
        bindNullableText(23, p.quicSecurity)
        bindNullableText(24, p.quicKey)
        bindNullableText(25, p.mode)
        bindNullableText(26, p.serviceName)
        bindNullableText(27, p.authority)
        bindNullableText(28, p.xhttpMode)
        bindNullableText(29, p.xhttpExtra)
        bindNullableText(30, p.finalMask)
        bindNullableText(31, p.security)
        bindNullableText(32, p.sni)
        bindNullableText(33, p.alpn)
        bindNullableText(34, p.fingerPrint)
        bindNullableLong(35, p.insecure?.toSqlite())
        bindNullableText(36, p.echConfigList)
        bindNullableText(37, p.verifyPeerCertByName)
        bindNullableText(38, p.pinnedCA256)
        bindNullableText(39, p.publicKey)
        bindNullableText(40, p.shortId)
        bindNullableText(41, p.spiderX)
        bindNullableText(42, p.mldsa65Verify)
        bindNullableText(43, p.secretKey)
        bindNullableText(44, p.preSharedKey)
        bindNullableText(45, p.localAddress)
        bindNullableText(46, p.reserved)
        bindNullableLong(47, p.mtu?.toLong())
        bindNullableText(48, p.obfsPassword)
        bindNullableText(49, p.portHopping)
        bindNullableText(50, p.portHoppingInterval)
        bindNullableText(51, p.pinSHA256)
        bindNullableText(52, p.bandwidthDown)
        bindNullableText(53, p.bandwidthUp)
        bindNullableText(54, p.policyGroupType)
        bindNullableText(55, p.policyGroupSubscriptionId)
        bindNullableText(56, p.policyGroupFilter)
        bindNullableLong(57, p.policyGroupTestOutbounds?.toSqlite())
        bindNullableText(58, p.policyGroupFallbackTag)
        bindNullableText(59, p.proxyChainProfiles)
        bindNullableText(60, p.browserDialerMode)
    }

    private suspend fun insertStat(exec: SqlExec, row: StatRow) = exec(
        "INSERT OR REPLACE INTO profile_stats (guid, testDelayMillis) VALUES (?, ?)"
    ) {
        bindText(1, row.guid)
        bindLong(2, row.delay)
    }

    private suspend fun insertRaw(exec: SqlExec, row: RawRow) = exec(
        "INSERT OR REPLACE INTO profile_raw (guid, content) VALUES (?, ?)"
    ) {
        bindText(1, row.guid)
        bindText(2, row.content)
    }

    private suspend fun insertAsset(exec: SqlExec, asset: AssetUrlItem) =
        exec(
            """
            INSERT OR REPLACE INTO assets (guid, remarks, url, addedTime, lastUpdated, locked)
            VALUES (?,?,?,?,?,?)
            """
        ) {
            bindText(1, asset.guid)
            bindText(2, asset.remarks)
            bindText(3, asset.url)
            bindLong(4, asset.addedTime)
            bindLong(5, asset.lastUpdated)
            bindNullableLong(6, asset.locked?.toSqlite())
        }

    private suspend fun insertRule(
        exec: SqlExec,
        row: RuleRow,
    ) = exec(
        """
        INSERT OR REPLACE INTO routing_rules
          (id, sortOrder, remarks, ip, domain, process, outboundTag, port, network, protocol,
           enabled, locked)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """
    ) {
        val rule = row.rule
        bindText(1, row.id)
        bindLong(2, row.sortOrder)
        bindNullableText(3, rule.remarks)
        bindNullableText(4, rule.ip?.let(JsonUtil::toJson))
        bindNullableText(5, rule.domain?.let(JsonUtil::toJson))
        bindNullableText(6, rule.process?.let(JsonUtil::toJson))
        bindText(7, rule.outboundTag)
        bindNullableText(8, rule.port)
        bindNullableText(9, rule.network)
        bindNullableText(10, rule.protocol?.let(JsonUtil::toJson))
        bindLong(11, rule.enabled.toSqlite())
        bindNullableLong(12, rule.locked?.toSqlite())
    }

    private suspend fun putSetting(
        exec: SqlExec,
        row: SettingRow,
    ) = exec(
        "INSERT OR REPLACE INTO settings (key, value, kind) VALUES (?,?,?)"
    ) {
        bindText(1, row.key)
        bindNullableText(2, row.value)
        bindText(3, row.kind)
    }

    private fun SQLiteStatement.bindNullableText(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }

    private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun Boolean.toSqlite(): Long = if (this) 1L else 0L

    private const val PROFILE_INSERT =
        """
        INSERT OR REPLACE INTO profiles
          (guid, sortOrder, dedupeKey, configVersion, configType, subscriptionId, addedTime,
           remarks, description, server, serverPort,
           password, method, flow, username,
           network, headerType, host, path, seed, kcpMtu, kcpTti,
           quicSecurity, quicKey, mode, serviceName, authority, xhttpMode, xhttpExtra, finalMask,
           security, sni, alpn, fingerPrint, insecure, echConfigList, verifyPeerCertByName,
           pinnedCA256,
           publicKey, shortId, spiderX, mldsa65Verify,
           secretKey, preSharedKey, localAddress, reserved, mtu,
           obfsPassword, portHopping, portHoppingInterval, pinSHA256, bandwidthDown, bandwidthUp,
           policyGroupType, policyGroupSubscriptionId, policyGroupFilter,
           policyGroupTestOutbounds, policyGroupFallbackTag, proxyChainProfiles,
           browserDialerMode)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,
                ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """
}
