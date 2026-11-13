package com.v2ray.ang.data.repository

import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.data.dao.ProfileDao
import com.v2ray.ang.di.IoDispatcher
import com.v2ray.ang.dto.TaskerProfile
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
