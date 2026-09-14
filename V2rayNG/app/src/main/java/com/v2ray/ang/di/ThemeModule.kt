package com.v2ray.ang.di

import com.v2ray.ang.data.repository.ThemeRepository
import com.v2ray.ang.data.repository.ThemeStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * @Binds now that the implementation has a constructor. The binding stays lazy: nothing may
 * request ThemeStore before SettingsStore.refresh() has run, because construction reads the
 * snapshot.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ThemeModule {

    @Binds
    @Singleton
    abstract fun bindThemeStore(impl: ThemeRepository): ThemeStore
}
