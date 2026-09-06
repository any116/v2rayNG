package com.v2ray.ang.di

import javax.inject.Qualifier

/**
 * Marks the dispatcher used for blocking work: MMKV, files, network, PackageManager, root shell.
 *
 * The qualifier exists so a repository never hard-codes [kotlinx.coroutines.Dispatchers.IO];
 * unit tests inject a deterministic dispatcher instead of relying on a real thread pool.
 * See docs/project-rules/coroutine-flow-rules.md section 2 for the dispatcher decision table.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Marks the dispatcher used for CPU bound work: filtering, sorting, regex matching.
 *
 * Only inject this where the work is provably CPU bound. Calling a suspend repository method
 * still runs on the caller's dispatcher, because thread confinement is the repository's job.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
