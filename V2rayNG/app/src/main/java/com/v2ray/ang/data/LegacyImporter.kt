package com.v2ray.ang.data

import androidx.sqlite.SQLiteStatement
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.util.JsonUtil

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
 * Called by LegacyMigrationGate inside an immediateTransaction on a writer connection.
 */
internal object LegacyImporter {

    suspend fun importInto(exec: SqlExec, snapshot: LegacySnapshot) {
        if (snapshot.isEmpty) return
        val sortStep = ProfileItem.SORT_STEP

        // 1) Subscriptions: the subOrder index becomes sortOrder.
        snapshot.subOrder.forEachIndexed { index, subId ->
            val item = snapshot.subscriptions[subId] ?: return@forEachIndexed
            insertSubscription(exec, subId, (index + 1) * sortStep, item)
        }
        // Subscriptions present in SUB but absent from SUB_IDS still need a row.
        var tail = snapshot.subOrder.size
        snapshot.subscriptions.forEach { (subId, item) ->
            if (subId in snapshot.subOrder) return@forEach
            tail++
            insertSubscription(exec, subId, tail * sortStep, item)
        }

        if (AppConfig.DEFAULT_SUBSCRIPTION_ID !in snapshot.subscriptions) {
            insertSubscription(
                exec,
                AppConfig.DEFAULT_SUBSCRIPTION_ID,
                0L,
                SubscriptionItem(
                    guid = AppConfig.DEFAULT_SUBSCRIPTION_ID,
                    remarks = AppConfig.DEFAULT_SUBSCRIPTION_REMARKS,
                ),
            )
        }

        // 2) Profiles, group by group; the index inside a group becomes sortOrder.
        //    DEFAULT_SUBSCRIPTION_ID is emitted first when absent from subOrder, matching
        //    decodeAllServerList().
        val groupOrder = buildList {
            if (AppConfig.DEFAULT_SUBSCRIPTION_ID !in snapshot.subOrder) {
                add(AppConfig.DEFAULT_SUBSCRIPTION_ID)
            }
            addAll(snapshot.subOrder)
            addAll(snapshot.groups.keys)
        }.distinct()

        groupOrder.forEach { subId ->
            snapshot.groups[subId]?.forEachIndexed { index, (guid, profile) ->
                insertProfile(exec, guid, subId, (index + 1) * sortStep, profile)
                snapshot.stats[guid]?.let { delay ->
                    exec("INSERT OR REPLACE INTO profile_stats (guid, testDelayMillis) VALUES (?, ?)") {
                        bindText(1, guid)
                        bindLong(2, delay)
                        step()
                    }
                }
                snapshot.raws[guid]?.let { raw ->
                    exec("INSERT OR REPLACE INTO profile_raw (guid, content) VALUES (?, ?)") {
                        bindText(1, guid)
                        bindText(2, raw)
                        step()
                    }
                }
            }
        }

        // 3) Assets.
        snapshot.assets.forEach { insertAsset(exec, it) }

        // 4) Routing rules: the array index becomes sortOrder.
        snapshot.rulesets.forEachIndexed { index, rule ->
            insertRule(exec, rule, (index + 1) * sortStep)
        }

        // 5) Scalar preferences, SELECTED_SERVER and WEBDAV_CONFIG.
        snapshot.settings.forEach { entry ->
            putSetting(exec, entry.key, entry.value, entry.kind)
        }

        // 6) dedupeKey algorithm version, for later comparison and backfill.
        putSetting(
            exec,
            SettingsStore.KEY_DEDUPE_ALGO_VERSION,
            ProfileItem.DEDUPE_ALGO_VERSION.toString(),
            SettingsStore.KIND_INT,
        )
    }

    private suspend fun insertSubscription(
        exec: SqlExec,
        guid: String,
        sortOrder: Long,
        item: SubscriptionItem,
    ) = exec(
        """
        INSERT OR REPLACE INTO subscriptions
          (guid, sortOrder, remarks, url, enabled, addedTime, lastUpdated, autoUpdate,
           updateInterval, prevProfile, nextProfile, filter, allowInsecureUrl,
           userAgent, requestHeaders)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """
    ) {
        bindText(1, guid)
        bindLong(2, sortOrder)
        bindText(3, item.remarks)
        bindText(4, item.url)
        bindLong(5, item.enabled.toSqlite())
        bindLong(6, item.addedTime)
        bindLong(7, item.lastUpdated)
        bindLong(8, item.autoUpdate.toSqlite())
        bindLong(9, item.updateInterval)
        bindNullableText(10, item.prevProfile)
        bindNullableText(11, item.nextProfile)
        bindNullableText(12, item.filter)
        bindLong(13, item.allowInsecureUrl.toSqlite())
        bindNullableText(14, item.userAgent)
        bindNullableText(15, item.requestHeaders)
        step()
    }

    @Suppress("DEPRECATION")
    private suspend fun insertProfile(
        exec: SqlExec,
        guid: String,
        subscriptionId: String,
        sortOrder: Long,
        p: ProfileItem,
    ) = exec(PROFILE_INSERT) {
        bindText(1, guid)
        bindLong(2, sortOrder)
        bindText(3, "")                      // dedupeKey: lazily backfilled
        bindLong(4, p.configVersion.toLong())
        bindLong(5, p.configType.value.toLong())
        bindText(6, subscriptionId)
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
        step()
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
            step()
        }

    private suspend fun insertRule(
        exec: SqlExec,
        rule: RulesetItem,
        sortOrder: Long,
    ) = exec(
        """
        INSERT OR REPLACE INTO routing_rules
          (id, sortOrder, remarks, ip, domain, process, outboundTag, port, network, protocol,
           enabled, locked)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """
    ) {
        // An id-less legacy rule would collide on the primary key; give it a stable synthetic one.
        bindText(1, rule.id.ifBlank { "legacy-$sortOrder" })
        bindLong(2, sortOrder)
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
        step()
    }

    private suspend fun putSetting(
        exec: SqlExec,
        key: String,
        value: String?,
        kind: String,
    ) = exec(
        "INSERT OR REPLACE INTO settings (key, value, kind) VALUES (?,?,?)"
    ) {
        bindText(1, key)
        bindNullableText(2, value)
        bindText(3, kind)
        step()
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
