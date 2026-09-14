package com.v2ray.ang.di

import android.content.Context
import com.v2ray.ang.data.AssetDao
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.RoutingDao
import com.v2ray.ang.data.SettingsDao
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.SubscriptionDao
import com.v2ray.ang.data.repository.ThemeStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceEntryPoint {

    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher

    fun profileDao(): ProfileDao
    fun subscriptionDao(): SubscriptionDao
    fun routingDao(): RoutingDao
    fun assetDao(): AssetDao
    fun settingsDao(): SettingsDao
    fun settingsStore(): SettingsStore
    fun themeStore(): ThemeStore
}

internal object PlatformDependencies {

    fun ioDispatcher(context: Context): CoroutineDispatcher = entryPoint(context).ioDispatcher()

    fun profileDao(context: Context): ProfileDao = entryPoint(context).profileDao()

    fun subscriptionDao(context: Context): SubscriptionDao = entryPoint(context).subscriptionDao()

    fun routingDao(context: Context): RoutingDao = entryPoint(context).routingDao()

    fun assetDao(context: Context): AssetDao = entryPoint(context).assetDao()

    fun settingsStore(context: Context): SettingsStore = entryPoint(context).settingsStore()

    fun themeStore(context: Context): ThemeStore = entryPoint(context).themeStore()

    private fun entryPoint(context: Context): ServiceEntryPoint =
        EntryPointAccessors.fromApplication(context, ServiceEntryPoint::class.java)
}
