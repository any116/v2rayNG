package com.v2ray.ang.di

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.v2ray.ang.data.AppDatabase
import com.v2ray.ang.data.AssetDao
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
     * This provider is deliberately free of any I/O: Hilt resolves it from Application field
     * injection, i.e. on the main thread, so opening the database, reading PRAGMA user_version
     * or quarantining a corrupt file all happen later, on the caller's dispatcher. The legacy
     * MMKV import runs through LegacyMigrationGate rather than a RoomDatabase.Callback, so a
     * failed import cannot take the database create transaction down with it.
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
        .build()

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
