package com.v2ray.ang.data.repository

import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.data.dao.ProfileDao
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.TaskerProfile
import com.v2ray.ang.handler.AngConfigManager
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

open class ShortcutRepository @Inject constructor(
    private val profileDao: ProfileDao,
    @IoDispatcher io: CoroutineDispatcher
) : BaseRepository(io) {

    /**
     * Probes the core. Only meaningful in `:RunSoLibV2RayDaemon`, which is where the three service
     * entries are declared to run; calling it from the UI process would load the native libs there.
     */
    open suspend fun isCoreRunning(): Boolean = withIO { CoreServiceManager.isRunning() }

    /**
     * Imports profiles and subscriptions from scanned text. Like the main screen and URL-scheme
     * imports, this must retain the default group's existing rows and prepend the new batch.
     * Replacement is reserved for subscription refreshes, never a user-initiated scan.
     *
     * @return the total number of imported profiles and subscriptions.
     */
    open suspend fun importBatchConfig(text: String): Int = withIO {
        val (count, countSub) = AngConfigManager.importBatchConfig(
            server = text,
            subid = "",
            append = true
        )
        count + countSub
    }

    /**
     * All stored profiles, in list order.
     */
    open suspend fun loadTaskerProfiles(): List<TaskerProfile> = withIO {
        profileDao.allGuidsInOrder().mapNotNull { guid ->
            profileDao.findByGuid(guid)?.let { config ->
                TaskerProfile(guid = guid, remarks = config.remarks)
            }
        }
    }
}
