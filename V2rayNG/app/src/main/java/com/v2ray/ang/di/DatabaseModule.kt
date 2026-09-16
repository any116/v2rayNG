package com.v2ray.ang.di

import android.app.Application
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
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
     */
    @Provides
    @Singleton
    fun provideDatabase(
        app: Application,
        @IoDispatcher io: CoroutineDispatcher,
        @ApplicationScope appScope: CoroutineScope,
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
                            // process restarted. Consume it once.
                            val json = staged.readText()
                            staged.delete()
                            JsonUtil.fromJsonSafe(json, LegacySnapshot::class.java)
                                ?: error("Pending legacy snapshot failed to deserialize")
                        } else {
                            MmkvLegacyReader(app).readAll()
                        }
                    }
                )
                .enableMultiInstanceInvalidation()
                .build()
        }

        // build() only constructs the wrapper.
        if (!app.getDatabasePath(AppDatabase.NAME).exists()) return build()

        val db = runCatching { build() }.getOrElse { error ->
            LogUtil.e(AppConfig.TAG, "Building the database wrapper failed", error)
            quarantine(app)
            return build()
        }

        return runCatching {
            runBlocking(io) {
                db.useReaderConnection<Int> { conn ->
                    conn.usePrepared("PRAGMA user_version") { st ->
                        if (st.step()) st.getInt(0) else 0
                    }
                }
            }
            db
        }.getOrElse { error ->
            LogUtil.e(AppConfig.TAG, "Opening the database failed; quarantining the file", error)
            runCatching { db.close() }
            quarantine(app)
            build()
        }.also { opened ->
            appScope.launch {
                runCatching {
                    opened.useWriterConnection<Unit> { conn ->
                        conn.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { st ->
                            st.step()
                        }
                    }
                }.onFailure {
                    LogUtil.w(AppConfig.TAG, "Post-open WAL checkpoint skipped", it)
                }
            }
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
