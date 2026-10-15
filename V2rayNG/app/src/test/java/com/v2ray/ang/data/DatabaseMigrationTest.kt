package com.v2ray.ang.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class DatabaseMigrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun migration2To3PreservesPayloadUnknownTypeAndReinstallsTriggers() = runTest {
        val helper = MigrationTestHelper(
            schemaDirectoryPath = sequenceOf(
                Path.of("schemas"),
                Path.of("app", "schemas"),
                Path.of("V2rayNG", "app", "schemas"),
            ).first { Files.isDirectory(it) },
            databasePath = tempDir.resolve("migration.db"),
            driver = BundledSQLiteDriver(),
            databaseClass = AppDatabase::class,
        )
        val old = helper.createDatabase(2)
        try {
            old.prepare(
                """
                INSERT INTO profiles(
                    guid, sortOrder, groupSortOrder, dedupeKey, configVersion, configType,
                    subscriptionId, addedTime, remarks, description, server, serverPort,
                    password, network, security, insecure, remoteDNS
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                listOf(
                    Triple("legacy", 9001L, "secret"),
                    Triple("legacy-2", 1L, "secret-2"),
                ).forEachIndexed { index, (guid, configType, password) ->
                    statement.bindText(1, guid)
                    statement.bindLong(2, (index + 1) * 1024L)
                    statement.bindLong(3, Long.MAX_VALUE)
                    statement.bindText(4, "old-key-$index")
                    statement.bindLong(5, 7L)
                    statement.bindLong(6, configType)
                    statement.bindText(7, "sub")
                    statement.bindLong(8, 123L + index)
                    statement.bindText(9, "legacy node $index")
                    statement.bindText(10, "description")
                    statement.bindText(11, "1.2.3.${index + 4}")
                    statement.bindText(12, "443")
                    statement.bindText(13, password)
                    statement.bindText(14, "ws")
                    statement.bindText(15, "tls")
                    statement.bindLong(16, 1L)
                    statement.bindText(17, "8.8.8.8")
                    statement.step()
                    statement.reset()
                }
            }
        } finally {
            old.close()
        }

        val migrated = helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3))
        try {
            migrated.prepare(
                "SELECT configType, groupSortOrder, insecure FROM profiles WHERE guid = 'legacy'"
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals(9001L, statement.getLong(0))
                assertEquals(Long.MAX_VALUE, statement.getLong(1))
                assertEquals(1L, statement.getLong(2))
            }

            migrated.prepare(
                "SELECT payloadVersion, content, dedupeKey FROM profile_payload WHERE guid = 'legacy'"
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals(PROFILE_PAYLOAD_VERSION.toLong(), statement.getLong(0))
                val content = statement.getText(1)
                assertTrue(content.contains("\"configVersion\":7"))
                assertTrue(content.contains("\"password\":\"secret\""))
                assertTrue(content.contains("\"remoteDNS\":\"8.8.8.8\""))
                assertFalse(content.contains("\"network\""))
                assertEquals("", statement.getText(2))
            }

            migrated.prepare("SELECT COUNT(*) FROM profile_payload").use { statement ->
                assertTrue(statement.step())
                assertEquals(2L, statement.getLong(0))
            }

            GROUP_ORDER_TRIGGER_NAMES.forEach { name ->
                migrated.prepare(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = ?"
                ).use { statement ->
                    statement.bindText(1, name)
                    assertTrue(statement.step())
                    assertEquals(1L, statement.getLong(0))
                }
            }

            migrated.prepare(
                """
                INSERT INTO subscriptions(
                    guid, sortOrder, remarks, url, enabled, addedTime, lastUpdated,
                    autoUpdate, updateInterval, allowInsecureUrl
                ) VALUES ('sub', 4096, 'subscription', 'https://example.test/sub', 1, 0, 0, 0, 0, 0)
                """.trimIndent()
            ).use { it.step() }
            migrated.prepare("SELECT groupSortOrder FROM profiles WHERE guid = 'legacy'").use { statement ->
                assertTrue(statement.step())
                assertEquals(4096L, statement.getLong(0))
            }
        } finally {
            migrated.close()
        }
    }
}
