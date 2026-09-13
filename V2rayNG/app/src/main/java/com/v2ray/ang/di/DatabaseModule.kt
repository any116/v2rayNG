package com.v2ray.ang.di

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.AppDatabase
import com.v2ray.ang.data.AssetDao
import com.v2ray.ang.data.LegacyImportCallback
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.RoutingDao
import com.v2ray.ang.data.SettingsDao
import com.v2ray.ang.data.SubscriptionDao
import com.v2ray.ang.data.legacy.LegacySnapshot
import com.v2ray.ang.data.legacy.MmkvLegacyReader
import com.v2ray.ang.data.repository.BackupRepository
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Every process (UI, :daemon, :tasks, :bg) builds its own Application and its own Hilt
     * graph, so each holds its own instance. enableMultiInstanceInvalidation() is what makes one
     * process' write invalidate another's Flow / PagingSource, replacing MMKV's
     * MULTI_PROCESS_MODE. It must be enabled in EVERY process: enabling it on one side only is
     * the same as not enabling it.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        app: Application,
        @IoDispatcher io: CoroutineDispatcher,
    ): AppDatabase {
        val build = {
            Room.databaseBuilder<AppDatabase>(app, AppDatabase.NAME)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(io)
                .addCallback(
                    LegacyImportCallback {
                        val staged = BackupRepository.pendingSnapshotFile(app)
                        if (staged.isFile) {
                            // A restored legacy archive staged its snapshot here before the
                            // process restarted. Consume it once; if deserialization fails,
                            // fall through to an empty snapshot rather than crashing the
                            // first process that opens the database.
                            val snapshot = runCatching {
                                JsonUtil.fromJsonSafe(staged.readText(), LegacySnapshot::class.java)
                            }.getOrNull()
                            staged.delete()
                            snapshot ?: LegacySnapshot.EMPTY
                        } else {
                            // First-time creation on an existing install: import from MMKV.
                            MmkvLegacyReader().readAll()
                        }
                    }
                )
                .enableMultiInstanceInvalidation()
                .build()
        }
        return runCatching { build() }.getOrElse { error ->
            // Replaces MMKV's onMMKVCRCCheckFail / onMMKVFileLengthError. The corrupt file is
            // renamed rather than deleted so the user can still export it for diagnosis; this is
            // a net loss of capability compared to MMKV's partial recovery and is recorded as
            // such in the migration document.
            LogUtil.e(AppConfig.TAG, "Opening the database failed; quarantining the file", error)
            quarantine(app)
            build()
        }
    }

    private fun quarantine(app: Application) {
        val file = app.getDatabasePath(AppDatabase.NAME)
        val stamp = System.currentTimeMillis()
        runCatching { file.renameTo(File(file.parentFile, "${AppDatabase.NAME}.corrupt.$stamp")) }
        runCatching { File("${file.path}-wal").delete() }
        runCatching { File("${file.path}-shm").delete() }
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
