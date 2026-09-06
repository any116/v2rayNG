package com.v2ray.ang.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Supplies the two dispatchers the data layer is allowed to use.
 *
 * The bindings are unscoped on purpose: [Dispatchers.IO] and [Dispatchers.Default] are already
 * process wide singletons, adding @Singleton would only add a double-check lock around a
 * constant. Nothing else may be added here; a dispatcher that is derived per feature, such as
 * `Dispatchers.IO.limitedParallelism(1)` in MainViewModel, stays owned by that feature.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    /**
     * @return the dispatcher every `BaseRepository.withIO { }` call runs on.
     */
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * @return the dispatcher for CPU bound work started with `launch(context = ...)`.
     */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
