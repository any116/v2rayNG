package com.v2ray.ang.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Supplies the two dispatchers the data layer is allowed to use, plus the single process
 * lifetime scope.
 *
 * The dispatcher bindings are unscoped on purpose: [Dispatchers.IO] and [Dispatchers.Default]
 * are already process wide singletons. Nothing else may be added here; a dispatcher derived per
 * feature stays owned by that feature.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * SupervisorJob so one failing collector cannot take the whole scope down. It is never
     * cancelled: its lifetime is the process, and the process dying cancels it for us.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher io: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + io)
}
