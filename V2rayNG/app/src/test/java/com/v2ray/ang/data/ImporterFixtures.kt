package com.v2ray.ang.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.SubscriptionItem
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
