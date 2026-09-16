package com.v2ray.ang.data

import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import com.v2ray.ang.data.entities.SettingsEntry
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil

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
