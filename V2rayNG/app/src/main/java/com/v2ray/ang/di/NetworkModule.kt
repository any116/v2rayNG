package com.v2ray.ang.di

import com.v2ray.ang.util.HttpUtil
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Re-exports the HTTP client that already exists in the codebase.
 *
 * This module deliberately does not build a client of its own. Subscription fetching, delay
 * testing and update checking run in `:RunSoLibV2RayDaemon` and `:bg` as well, where there is no
 * Hilt graph and where [HttpUtil] is called directly. 
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * @return the process wide base client owned by [HttpUtil]; per-call timeouts, proxy and
     * redirect policy are derived from it with [OkHttpClient.newBuilder].
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = HttpUtil.sharedClient
}
