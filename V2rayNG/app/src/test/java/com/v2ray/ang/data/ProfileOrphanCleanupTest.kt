package com.v2ray.ang.data

import com.v2ray.ang.data.entities.ProfilePayloadItem
import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.data.entities.ServerAffiliationInfo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class ProfileOrphanCleanupTest {

    @Test
    fun cleanupOrphansRemovesBothDirectionsAndKeepsSelectionUsable() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            val complete = profile("complete")
            val metadataOnly = profile("metadata-only")
            val payloadOnly = profile("payload-only")

            dao.upsert(complete)
            dao.upsertMetadata(metadataOnly.toMetadataItem())
            dao.upsertPayload(payloadOnly.toPayloadItem())
            dao.upsertStats(
                listOf(
                    ServerAffiliationInfo(guid = metadataOnly.guid, testDelayMillis = 42L),
                    ServerAffiliationInfo(guid = "stats-only", testDelayMillis = 42L),
                )
            )
            dao.putRaw(ProfileRaw(metadataOnly.guid, "metadata raw"))
            dao.putRaw(ProfileRaw("raw-only", "orphan raw"))
            dao.putSelectedGuid(metadataOnly.guid)

            assertEquals(listOf(metadataOnly.guid), dao.orphanMetadataGuids())

            val removed = dao.cleanupOrphans()

            assertEquals(4, removed)
            assertNotNull(dao.findByGuid(complete.guid))
            assertNull(dao.findMetadataByGuid(metadataOnly.guid))
            assertNull(dao.findPayloadByGuid(payloadOnly.guid))
            assertNull(dao.stats(metadataOnly.guid))
            assertNull(dao.stats("stats-only"))
            assertNull(dao.raw(metadataOnly.guid))
            assertNull(dao.raw("raw-only"))
            assertEquals(complete.guid, dao.selectedGuid())
            assertEquals(emptyList<String>(), dao.orphanMetadataGuids())
            assertEquals(0, dao.cleanupOrphans())
        } finally {
            db.close()
        }
    }

    @Test
    fun metadataWithUnsupportedPayloadIsNotTreatedAsMetadataOrphan() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            val profile = profile("future")
            dao.upsertMetadata(profile.toMetadataItem())
            dao.upsertPayload(
                ProfilePayloadItem(
                    guid = profile.guid,
                    payloadVersion = PROFILE_PAYLOAD_VERSION + 1,
                    content = "{\"configType\":\"VMESS\"}",
                )
            )

            assertEquals(emptyList<String>(), dao.orphanMetadataGuids())
            assertEquals(0, dao.cleanupOrphans())
            assertNotNull(dao.findMetadataByGuid(profile.guid))
            assertNotNull(dao.findPayloadByGuid(profile.guid))
        } finally {
            db.close()
        }
    }
}
