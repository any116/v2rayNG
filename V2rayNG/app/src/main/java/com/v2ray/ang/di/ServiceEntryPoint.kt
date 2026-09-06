package com.v2ray.ang.di

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The only sanctioned escape hatch out of the Hilt graph, for objects Hilt cannot construct.
 *
 * Hilt supports Application, Activity, Fragment, View, Service and BroadcastReceiver. It does not
 * support plain classes that are instantiated by the platform-facing layer itself, such as
 * [com.v2ray.ang.service.ProcessService], which is a helper around ProcessBuilder rather than an
 * android.app.Service despite its name.
 *
 * Migration plan section 11 allows a limited EntryPoint for exactly that case and forbids it
 * everywhere else: this must never degrade into a Service Locator for ordinary `repository/`
 * classes. Two rules keep it honest:
 *
 *  1. Only the system entry adapter layer (`service/`, `receiver/`) may call it. A Repository or a
 *     ViewModel that reaches for it is a bug — those have constructor injection.
 *  2. Only process-agnostic dependencies belong here. The accessor resolves the graph of the
 *     *calling* process, because every process builds its own Application and its own Hilt graph;
 *     the resolved object is never passed across a process boundary.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceEntryPoint {

    /**
     * @return the dispatcher for blocking work, the same binding `BaseRepository.withIO { }`
     * receives. Exposed so a platform helper stops hard-coding [kotlinx.coroutines.Dispatchers.IO]
     * and can be driven by a deterministic dispatcher in tests.
     */
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher
}

/**
 * Resolution helper for [ServiceEntryPoint]. Kept internal so the boundary cannot widen by accident.
 */
internal object PlatformDependencies {

    /**
     * Resolves the IO dispatcher from the Hilt graph of the process that owns [context].
     *
     * Safe from any process: `AngApplication` carries @HiltAndroidApp, so each process has a graph.
     * Must not be called before `AngApplication.onCreate()` has returned — that is where field
     * injection and `MmkvManager.initialize()` happen.
     */
    fun ioDispatcher(context: Context): CoroutineDispatcher =
        EntryPointAccessors.fromApplication(context, ServiceEntryPoint::class.java).ioDispatcher()
}
