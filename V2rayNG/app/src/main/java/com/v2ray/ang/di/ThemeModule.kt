package com.v2ray.ang.di

import com.v2ray.ang.repository.ThemeRepository
import com.v2ray.ang.repository.ThemeStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Re-exports the existing theme owner, in the same spirit as NetworkModule.
 *
 * @Provides rather than @Binds because the implementation is a Kotlin object: there is no
 * constructor for Dagger to call, and wrapping the object in a second @Singleton class is exactly
 * the "duplicate singleton" the migration plan forbids. @Singleton here only documents that the
 * graph hands out the one existing instance; it never creates one.
 *
 * The binding is not eager. Nothing may request ThemeStore before
 * AngApplication.onCreate() has run MmkvManager.initialize(), because touching the object reads
 * MMKV.
 */
@Module
@InstallIn(SingletonComponent::class)
object ThemeModule {

    /**
     * @return the process-wide theme state owner, the same instance ThemeManager exposes to
     * Compose. There is deliberately no second StateFlow.
     */
    @Provides
    @Singleton
    fun provideThemeStore(): ThemeStore = ThemeRepository
}
