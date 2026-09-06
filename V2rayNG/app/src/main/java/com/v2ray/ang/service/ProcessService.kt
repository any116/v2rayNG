package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Wrapper around ProcessBuilder for the separately packaged native binaries.
 */
class ProcessService {
    private var process: Process? = null

    /**
     * Owns every coroutine this class starts, so [stopProcess] can cancel the exit watcher instead
     * of leaving it parented to nothing. Rebuilt per run because a cancelled Job stays cancelled.
     */
    private var watchJob: Job? = null
    private var scope: CoroutineScope? = null

    /**
     * Runs a process with the given command.
     * @param context The context.
     * @param cmd The command to run.
     */
    fun runProcess(context: Context, cmd: MutableList<String>) {
        LogUtil.i(AppConfig.TAG, cmd.toString())

        try {
            val proBuilder = ProcessBuilder(cmd)
            proBuilder.redirectErrorStream(true)
            process = proBuilder
                .directory(context.filesDir)
                .start()

            // Dispatcher resolved from the calling process's graph; never cached across processes.
            val io = PlatformDependencies.ioDispatcher(context)
            val runScope = CoroutineScope(SupervisorJob() + io)
            scope = runScope

            watchJob = runScope.launch {
                Thread.sleep(50L)
                LogUtil.i(AppConfig.TAG, "runProcess check")
                process?.waitFor()
                LogUtil.i(AppConfig.TAG, "runProcess exited")
            }
            LogUtil.i(AppConfig.TAG, process.toString())

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, e.toString(), e)
        }
    }

    /**
     * Stops the running process.
     *
     * Cancels the watcher before destroying the process: teardown must prevent new work before
     * releasing the resource it observes. Repeated calls are no-ops.
     */
    fun stopProcess() {
        try {
            LogUtil.i(AppConfig.TAG, "runProcess destroy")
            watchJob?.cancel()
            watchJob = null
            scope?.cancel()
            scope = null
            process?.destroy()
            process = null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to destroy process", e)
        }
    }
}
