package com.v2ray.ang.data

import com.v2ray.ang.data.entities.ProfileMetadataItem
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfilePayloadItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

internal class ProfileStorageTest {

    @Test
    fun payloadRoundTripKeepsProtocolFieldsAndUsesMetadataAsTheListSource() {
        val original = profile("guid").apply {
            password = "secret"
            remoteDNS = "9.9.9.9"
            network = "ws"
            security = "tls"
        }
        val payload = original.toPayloadItem()
        assertFalse(payload.content.contains("\"guid\""))
        assertTrue(payload.content.contains("\"configType\""))
        val metadata = ProfileMetadataItem(
            guid = original.guid,
            sortOrder = 2048L,
            groupSortOrder = 1024L,
            configType = EConfigType.VMESS.value,
            subscriptionId = "new-group",
            addedTime = 7L,
            remarks = "renamed",
            description = "new description",
            server = "new.example.test",
            serverPort = "8443",
            network = "tcp",
            security = null,
            insecure = false,
        )

        val restored = metadata.merge(payload)

        assertEquals("secret", restored.password)
        assertEquals("9.9.9.9", restored.remoteDNS)
        assertEquals("renamed", restored.remarks)
        assertEquals("new-group", restored.subscriptionId)
        assertEquals("new.example.test", restored.server)
        assertEquals("tcp", restored.network)
        assertEquals(null, restored.security)

        val edited = original.copy(password = "changed").toPayloadItem(
            previousContent = "{\"futureOption\":\"keep\",\"password\":\"old\"}"
        )
        assertEquals(true, edited.content.contains("futureOption"))
        assertEquals(true, edited.content.contains("changed"))

        val cleared = original.copy(sni = null).toPayloadItem(
            previousContent = "{\"configType\":\"VMESS\",\"sni\":\"old.example.test\",\"futureOption\":\"keep\"}"
        )
        assertFalse(cleared.content.contains("old.example.test"))
        assertTrue(cleared.content.contains("futureOption"))
    }

    @Test
    fun payloadOnlyUpdateDoesNotRewriteListMetadata() = runTest {
        val db = inMemoryDatabase()
        try {
            val dao = db.profileDao()
            val original = profile("guid")
            dao.upsert(original)
            val before = dao.findMetadataByGuid(original.guid)
            dao.setDedupeKey(original.guid, "stale-key")

            dao.upsert(original.copy(password = "changed", dedupeKey = "stale-key"))

            assertEquals(before, dao.findMetadataByGuid(original.guid))
            assertEquals("changed", dao.findByGuid(original.guid)?.password)
            assertEquals("", dao.findPayloadByGuid(original.guid)?.dedupeKey)
        } finally {
            db.close()
        }
    }

    @Test
    fun retiredXdriveTuningFieldsAreRemovedWhenPayloadIsUpdated() {
        val payload = profile("guid").toPayloadItem(
            previousContent = """
                {
                  "configType":"VMESS",
                  "segmentBytes":1024,
                  "flushIntervalMs":10,
                  "futureOption":"keep"
                }
            """.trimIndent()
        )

        assertFalse(payload.content.contains("segmentBytes"))
        assertFalse(payload.content.contains("flushIntervalMs"))
        assertTrue(payload.content.contains("futureOption"))
    }

    @Test
    fun xdriveServiceFieldsRoundTripThroughThePayload() {
        val original = profile("xdrive", type = EConfigType.VLESS).apply {
            network = NetworkType.XDRIVE.type
            remoteFolder = "folder"
            service = "template"
            secrets = "user\npass"
            template = "{\"put\":{\"url\":\"https://storage.example/{name}\"}}"
        }

        val restored = original.toMetadataItem().merge(original.toPayloadItem())

        assertEquals(original.remoteFolder, restored.remoteFolder)
        assertEquals(original.service, restored.service)
        assertEquals(original.secrets, restored.secrets)
        assertEquals(original.template, restored.template)
    }

    @Test
    fun masqueWarpFieldsRoundTripThroughThePayload() {
        val original = profile("warp", type = EConfigType.MASQUE).apply {
            masqueWarp = true
            masqueWarpPrivateKey = "private-key"
            masqueWarpPublicKey = "public-key"
            masqueWarpAddress = "172.16.0.2/32,2606:4700:110:8a36::2/128"
        }

        val restored = original.toMetadataItem().merge(original.toPayloadItem())

        assertTrue(restored.masqueWarp)
        assertEquals(original.masqueWarpPrivateKey, restored.masqueWarpPrivateKey)
        assertEquals(original.masqueWarpPublicKey, restored.masqueWarpPublicKey)
        assertEquals(original.masqueWarpAddress, restored.masqueWarpAddress)
    }

    @Test
    fun knownFieldsCoversEverySerializableProfileProperty() {
        val declared = ProfileItem::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) || Modifier.isTransient(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(emptySet<String>(), declared - ProfilePayloadCodec.knownFieldNames)
    }

    @Test
    fun unknownProtocolTypeSurvivesMetadataRoundTripAndChangesIdentity() {
        val payload = profile("guid").toPayloadItem()
        val metadata = ProfileMetadataItem(
            guid = "guid",
            sortOrder = 0L,
            configType = 9001,
            subscriptionId = "sub",
            addedTime = 0L,
            remarks = "node",
            description = null,
            server = "1.2.3.4",
            serverPort = "443",
            network = null,
            security = null,
            insecure = null,
        )

        val restored = metadata.merge(payload)

        assertEquals(EConfigType.CUSTOM, restored.configType)
        assertEquals(9001, restored.persistedConfigType)
        assertEquals(9001, restored.toMetadataItem().configType)
        assertNull(profile("known").persistedConfigType)
        assertFalse(restored.computeDedupeKey() == restored.copy(persistedConfigType = null).computeDedupeKey())
    }

    @Test
    fun malformedOrUnsupportedPayloadIsIgnoredBySafeMerge() {
        val metadata = profile("guid").toMetadataItem()

        assertNull(metadata.mergeOrNull(null))
        assertNull(
            metadata.mergeOrNull(
                ProfilePayloadItem("guid", PROFILE_PAYLOAD_VERSION + 1, "{\"configType\":\"VMESS\"}")
            )
        )
        assertNull(metadata.mergeOrNull(ProfilePayloadItem("guid", PROFILE_PAYLOAD_VERSION, "{")))
        assertNull(metadata.mergeOrNull(ProfilePayloadItem("guid", PROFILE_PAYLOAD_VERSION, "{}")))
    }
}
