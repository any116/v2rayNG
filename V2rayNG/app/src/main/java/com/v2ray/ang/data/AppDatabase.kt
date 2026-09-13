package com.v2ray.ang.data

import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.data.entities.SettingsEntry
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

object DbConverters {

    @ColumnTypeConverter
    fun configTypeToInt(value: EConfigType): Int = value.value

    @ColumnTypeConverter
    fun intToConfigType(value: Int): EConfigType =
        EConfigType.fromInt(value) ?: EConfigType.CUSTOM

    @ColumnTypeConverter
    fun stringListToJson(value: List<String>?): String? = value?.let(JsonUtil::toJson)

    @ColumnTypeConverter
    fun jsonToStringList(value: String?): List<String>? =
        if (value.isNullOrBlank()) null
        else JsonUtil.fromJsonSafe(value, Array<String>::class.java)?.toList()
}

@Database(
    entities = [
        ProfileItem::class,
        ServerAffiliationInfo::class,
        ProfileRaw::class,
        SubscriptionItem::class,
        AssetUrlItem::class,
        RulesetItem::class,
        SettingsEntry::class,
    ],
    version = 1,
    exportSchema = true,
)
@ColumnTypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun assetDao(): AssetDao
    abstract fun routingDao(): RoutingDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val NAME = "v2rayng.db"
    }
}

/**
 * One-shot MMKV -> Room migration, executed only while creating v1. Room's user_version is the
 * idempotency marker, and onCreate runs inside the create transaction, so either the database
 * and the data both exist or neither does.
 *
 * MMKV data is deliberately NOT deleted: it is the rollback path.
 */
internal class LegacyImportCallback(
    private val snapshotProvider: suspend () -> LegacySnapshot,
) : RoomDatabase.Callback() {

    override suspend fun onCreate(connection: SQLiteConnection) {
        val snapshot = runCatching { snapshotProvider() }
            .onFailure { LogUtil.e(AppConfig.TAG, "Legacy read failed; creating an empty database", it) }
            .getOrDefault(LegacySnapshot.EMPTY)
        LegacyImporter.importInto(connection, snapshot)
    }
}
