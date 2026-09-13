package com.v2ray.ang.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
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
 */
internal object LegacyImporter {

    suspend fun importInto(connection: SQLiteConnection, snapshot: LegacySnapshot) {
        if (snapshot.isEmpty) return
        val step = ProfileItem.SORT_STEP

        // 1) Subscriptions: the subOrder index becomes sortOrder.
        snapshot.subOrder.forEachIndexed { index, subId ->
            val item = snapshot.subscriptions[subId] ?: return@forEachIndexed
            insertSubscription(connection, subId, (index + 1) * step, item)
        }
        // Subscriptions present in SUB but absent from SUB_IDS still need a row.
        var tail = snapshot.subOrder.size
        snapshot.subscriptions.forEach { (subId, item) ->
            if (subId in snapshot.subOrder) return@forEach
            tail++
            insertSubscription(connection, subId, tail * step, item)
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
                insertProfile(connection, guid, subId, (index + 1) * step, profile)
                snapshot.stats[guid]?.let { delay ->
                    connection.prepare(
                        "INSERT OR REPLACE INTO profile_stats (guid, testDelayMillis) VALUES (?, ?)"
                    ).use { st ->
                        st.bindText(1, guid)
                        st.bindLong(2, delay)
                        st.step()
                    }
                }
                snapshot.raws[guid]?.let { raw ->
                    connection.prepare(
                        "INSERT OR REPLACE INTO profile_raw (guid, content) VALUES (?, ?)"
                    ).use { st ->
                        st.bindText(1, guid)
                        st.bindText(2, raw)
                        st.step()
                    }
                }
            }
        }

        // 3) Assets.
        snapshot.assets.forEach { insertAsset(connection, it) }

        // 4) Routing rules: the array index becomes sortOrder.
        snapshot.rulesets.forEachIndexed { index, rule ->
            insertRule(connection, rule, (index + 1) * step)
        }

        // 5) Scalar preferences, SELECTED_SERVER and WEBDAV_CONFIG.
        snapshot.settings.forEach { entry ->
            putSetting(connection, entry.key, entry.value, entry.kind)
        }

        // 6) dedupeKey algorithm version, for later comparison and backfill.
        putSetting(
            connection,
            SettingsStore.KEY_DEDUPE_ALGO_VERSION,
            ProfileItem.DEDUPE_ALGO_VERSION.toString(),
            SettingsStore.KIND_INT,
        )

        connection.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    private suspend fun insertSubscription(
        connection: SQLiteConnection,
        guid: String,
        sortOrder: Long,
        item: SubscriptionItem,
    ) = connection.prepare(
        """
        INSERT OR REPLACE INTO subscriptions
          (guid, sortOrder, remarks, url, enabled, addedTime, lastUpdated, autoUpdate,
           updateInterval, prevProfile, nextProfile, filter, allowInsecureUrl,
           userAgent, requestHeaders)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """
    ).use { st ->
        st.bindText(1, guid)
        st.bindLong(2, sortOrder)
        st.bindText(3, item.remarks)
        st.bindText(4, item.url)
        st.bindLong(5, item.enabled.toSqlite())
        st.bindLong(6, item.addedTime)
        st.bindLong(7, item.lastUpdated)
        st.bindLong(8, item.autoUpdate.toSqlite())
        st.bindLong(9, item.updateInterval)
        st.bindNullableText(10, item.prevProfile)
        st.bindNullableText(11, item.nextProfile)
        st.bindNullableText(12, item.filter)
        st.bindLong(13, item.allowInsecureUrl.toSqlite())
        st.bindNullableText(14, item.userAgent)
        st.bindNullableText(15, item.requestHeaders)
        st.step()
    }

    @Suppress("DEPRECATION")
    private suspend fun insertProfile(
        connection: SQLiteConnection,
        guid: String,
        subscriptionId: String,
        sortOrder: Long,
        p: ProfileItem,
    ) = connection.prepare(PROFILE_INSERT).use { st ->
        st.bindText(1, guid)
        st.bindLong(2, sortOrder)
        st.bindText(3, "")                      // dedupeKey: lazily backfilled
        st.bindLong(4, p.configVersion.toLong())
        st.bindLong(5, p.configType.value.toLong())
        st.bindText(6, subscriptionId)
        st.bindLong(7, p.addedTime)
        st.bindText(8, p.remarks)
        st.bindNullableText(9, p.description)
        st.bindNullableText(10, p.server)
        st.bindNullableText(11, p.serverPort)
        st.bindNullableText(12, p.password)
        st.bindNullableText(13, p.method)
        st.bindNullableText(14, p.flow)
        st.bindNullableText(15, p.username)
        st.bindNullableText(16, p.network)
        st.bindNullableText(17, p.headerType)
        st.bindNullableText(18, p.host)
        st.bindNullableText(19, p.path)
        st.bindNullableText(20, p.seed)
        st.bindNullableLong(21, p.kcpMtu?.toLong())
        st.bindNullableLong(22, p.kcpTti?.toLong())
        st.bindNullableText(23, p.quicSecurity)
        st.bindNullableText(24, p.quicKey)
        st.bindNullableText(25, p.mode)
        st.bindNullableText(26, p.serviceName)
        st.bindNullableText(27, p.authority)
        st.bindNullableText(28, p.xhttpMode)
        st.bindNullableText(29, p.xhttpExtra)
        st.bindNullableText(30, p.finalMask)
        st.bindNullableText(31, p.security)
        st.bindNullableText(32, p.sni)
        st.bindNullableText(33, p.alpn)
        st.bindNullableText(34, p.fingerPrint)
        st.bindNullableLong(35, p.insecure?.toSqlite())
        st.bindNullableText(36, p.echConfigList)
        st.bindNullableText(37, p.verifyPeerCertByName)
        st.bindNullableText(38, p.pinnedCA256)
        st.bindNullableText(39, p.publicKey)
        st.bindNullableText(40, p.shortId)
        st.bindNullableText(41, p.spiderX)
        st.bindNullableText(42, p.mldsa65Verify)
        st.bindNullableText(43, p.secretKey)
        st.bindNullableText(44, p.preSharedKey)
        st.bindNullableText(45, p.localAddress)
        st.bindNullableText(46, p.reserved)
        st.bindNullableLong(47, p.mtu?.toLong())
        st.bindNullableText(48, p.obfsPassword)
        st.bindNullableText(49, p.portHopping)
        st.bindNullableText(50, p.portHoppingInterval)
        st.bindNullableText(51, p.pinSHA256)
        st.bindNullableText(52, p.bandwidthDown)
        st.bindNullableText(53, p.bandwidthUp)
        st.bindNullableText(54, p.policyGroupType)
        st.bindNullableText(55, p.policyGroupSubscriptionId)
        st.bindNullableText(56, p.policyGroupFilter)
        st.bindNullableLong(57, p.policyGroupTestOutbounds?.toSqlite())
        st.bindNullableText(58, p.policyGroupFallbackTag)
        st.bindNullableText(59, p.proxyChainProfiles)
        st.bindNullableText(60, p.browserDialerMode)
        st.step()
    }

    private suspend fun insertAsset(connection: SQLiteConnection, asset: AssetUrlItem) =
        connection.prepare(
            """
            INSERT OR REPLACE INTO assets (guid, remarks, url, addedTime, lastUpdated, locked)
            VALUES (?,?,?,?,?,?)
            """
        ).use { st ->
            st.bindText(1, asset.guid)
            st.bindText(2, asset.remarks)
            st.bindText(3, asset.url)
            st.bindLong(4, asset.addedTime)
            st.bindLong(5, asset.lastUpdated)
            st.bindNullableLong(6, asset.locked?.toSqlite())
            st.step()
        }

    private suspend fun insertRule(
        connection: SQLiteConnection,
        rule: RulesetItem,
        sortOrder: Long,
    ) = connection.prepare(
        """
        INSERT OR REPLACE INTO routing_rules
          (id, sortOrder, remarks, ip, domain, process, outboundTag, port, network, protocol,
           enabled, locked)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """
    ).use { st ->
        // An id-less legacy rule would collide on the primary key; give it a stable synthetic one.
        st.bindText(1, rule.id.ifBlank { "legacy-$sortOrder" })
        st.bindLong(2, sortOrder)
        st.bindNullableText(3, rule.remarks)
        st.bindNullableText(4, rule.ip?.let(JsonUtil::toJson))
        st.bindNullableText(5, rule.domain?.let(JsonUtil::toJson))
        st.bindNullableText(6, rule.process?.let(JsonUtil::toJson))
        st.bindText(7, rule.outboundTag)
        st.bindNullableText(8, rule.port)
        st.bindNullableText(9, rule.network)
        st.bindNullableText(10, rule.protocol?.let(JsonUtil::toJson))
        st.bindLong(11, rule.enabled.toSqlite())
        st.bindNullableLong(12, rule.locked?.toSqlite())
        st.step()
    }

    private suspend fun putSetting(
        connection: SQLiteConnection,
        key: String,
        value: String?,
        kind: String,
    ) = connection.prepare(
        "INSERT OR REPLACE INTO settings (key, value, kind) VALUES (?,?,?)"
    ).use { st ->
        st.bindText(1, key)
        st.bindNullableText(2, value)
        st.bindText(3, kind)
        st.step()
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
