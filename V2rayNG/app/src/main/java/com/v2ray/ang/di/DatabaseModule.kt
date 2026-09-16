package com.v2ray.ang.di

import android.app.Application
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.v2ray.ang.data.AppDatabase
import com.v2ray.ang.data.AssetDao
import com.v2ray.ang.data.GROUP_ORDER_TRIGGERS
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.RoutingDao
import com.v2ray.ang.data.SettingsDao
import com.v2ray.ang.data.SubscriptionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Every process (UI, :daemon, :tasks, :bg) builds its own Application and its own Hilt
     * graph, each holds its own instance. enableMultiInstanceInvalidation() is what makes one
     * process' write invalidate another's Flow / PagingSource, replacing MMKV's
     * MULTI_PROCESS_MODE. It must be enabled in EVERY process: enabling it on one side only is
     * the same as not enabling it.
     *
     * build() is lazy: no file is opened here, and this provider runs on the main thread during
     * Application field injection, so an integrity check cannot live here. That check runs in
     * LegacyMigrationGate, under a cross-process file lock, before the first DAO call resolves
     * the database.
     *
     * fallbackToDestructiveMigrationOnDowngrade covers the "user reinstalled an older APK"
     * path, which otherwise throws on open. It does NOT cover the upgrade path: a missing
     * Migration for a bumped user_version still crashes on open, as it should — silently
     * dropping the user's data on an upgrade would be worse than crashing.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        app: Application,
        @IoDispatcher io: CoroutineDispatcher,
    ): AppDatabase = Room.databaseBuilder<AppDatabase>(app, AppDatabase.NAME)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(io)
        .enableMultiInstanceInvalidation()
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .addCallback(object : RoomDatabase.Callback() {
            override suspend fun onCreate(connection: SQLiteConnection) {
                installGroupOrderTriggers(connection)
            }

            override suspend fun onOpen(connection: SQLiteConnection) {
                installGroupOrderTriggers(connection)
            }
        })
        .build()

    private suspend fun installGroupOrderTriggers(connection: SQLiteConnection) {
        GROUP_ORDER_TRIGGERS.forEach { sql ->
            connection.prepare(sql).use { statement ->
                statement.step()
            }
        }
    }

    @Provides
    fun profileDao(db: AppDatabase): ProfileDao = db.profileDao()

    @Provides
    fun subscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptionDao()

    @Provides
    fun assetDao(db: AppDatabase): AssetDao = db.assetDao()

    @Provides
    fun routingDao(db: AppDatabase): RoutingDao = db.routingDao()

    @Provides
    fun settingsDao(db: AppDatabase): SettingsDao = db.settingsDao()
}
