package com.v2ray.ang.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.entities.SettingEntry
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers

internal fun inMemoryDatabase(): AppDatabase = Room.inMemoryDatabaseBuilder<AppDatabase>()
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .addCallback(object : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            installGroupOrderTriggersForTest(connection)
        }

        override suspend fun onOpen(connection: SQLiteConnection) {
            installGroupOrderTriggersForTest(connection)
        }
    })
    .build()

/**
 * Runs the same transfer the migration gate runs, minus the gate's file lock and verification:
 * DAO tests need populated tables, not the retry machinery.
 */
internal suspend fun importLegacy(db: AppDatabase, snapshot: LegacySnapshot) {
    val plan = LegacyImporter.plan(snapshot)
    db.useWriterConnection { conn ->
        conn.immediateTransaction {
            LegacyImporter.importInto(conn.asSqlExec(), plan)
        }
    }
}

private suspend fun installGroupOrderTriggersForTest(connection: SQLiteConnection) {
    GROUP_ORDER_TRIGGERS.forEach { sql ->
        connection.prepare(sql).use { statement -> statement.step() }
    }
}

internal fun profile(
    guid: String,
    remarks: String = guid,
    server: String? = "1.2.3.4",
    port: String? = "443",
    type: EConfigType = EConfigType.VMESS,
    subscriptionId: String = AppConfig.DEFAULT_SUBSCRIPTION_ID,
): ProfileItem = ProfileItem(
    guid = guid,
    configType = type,
    subscriptionId = subscriptionId,
    addedTime = 0L,
    remarks = remarks,
    server = server,
    serverPort = port,
)

internal fun subscription(guid: String, remarks: String = guid): SubscriptionItem =
    SubscriptionItem(guid = guid, remarks = remarks, url = "https://example.test/$guid", addedTime = 0L)

/**
 * Pathological fixture set required by the migration plan: an empty group, an orphan payload
 * (already filtered by the reader, so absent here), a group missing from SUB_IDS, and settings
 * of every kind.
 */
internal fun legacySnapshot(): LegacySnapshot {
    val default = AppConfig.DEFAULT_SUBSCRIPTION_ID
    return LegacySnapshot(
        groups = linkedMapOf(
            default to listOf("d1", "d2").map { it to profile(it, subscriptionId = default) },
            "subA" to listOf("a1", "a2", "a3").map { it to profile(it, subscriptionId = "subA") },
            // Group absent from subOrder: must still be imported, after the ordered ones.
            "subOrphan" to listOf("o1" to profile("o1", subscriptionId = "subOrphan")),
        ),
        subOrder = listOf(default, "subA", "subEmpty"),
        subscriptions = mapOf(
            default to subscription(default, "Default"),
            "subA" to subscription("subA"),
            "subEmpty" to subscription("subEmpty"),
        ),
        stats = mapOf("a1" to 120L, "a2" to -1L),
        raws = mapOf("d1" to "{\"outbounds\":[]}"),
        assets = listOf(AssetUrlItem(guid = "asset1", remarks = "geoip.dat", url = "https://x.test/geoip.dat", addedTime = 1L)),
        rulesets = listOf(
            RulesetItem(id = "r1", outboundTag = AppConfig.TAG_DIRECT, domain = listOf(AppConfig.GEOSITE_PRIVATE)),
            RulesetItem(id = "r2", outboundTag = AppConfig.TAG_PROXY),
        ),
        settings = listOf(
            SettingEntry(AppConfig.PREF_CONFIRM_REMOVE, "true", SettingsStore.KIND_BOOL),
            SettingEntry(AppConfig.PREF_SOCKS_PORT, "10808", SettingsStore.KIND_STRING),
            SettingEntry(AppConfig.CACHE_LOGCAT_CLEARED_AT, "1700000000000", SettingsStore.KIND_LONG),
            SettingEntry(AppConfig.PREF_PER_APP_PROXY_SET, "[\"com.a\",\"com.b\"]", SettingsStore.KIND_SET),
            SettingEntry(SettingsStore.KEY_SELECTED_SERVER, "a2", SettingsStore.KIND_STRING),
        ),
    )
}
