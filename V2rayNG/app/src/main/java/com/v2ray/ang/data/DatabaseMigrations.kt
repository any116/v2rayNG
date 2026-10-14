package com.v2ray.ang.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** Adds the Xray WireGuard options without rewriting existing profile rows. */
val MIGRATION_1_2 = Migration(1, 2) { connection ->
    connection.prepare("ALTER TABLE `profiles` ADD COLUMN `remoteDNS` TEXT").use { it.step() }
}

/**
 * Splits list metadata from the complete protocol object.
 *
 * The migration rebuilds only the profiles table because SQLite cannot safely express the
 * required column removal as a portable Room schema change. Payload rows are written while the
 * old table still exists, so a failed migration rolls back both tables together.
 */
val MIGRATION_2_3 = Migration(2, 3) { connection ->
    // SQLite re-parses surviving subscriptions triggers while profiles is temporarily absent.
    // Drop all six first; DatabaseModule.onOpen recreates them after the rename completes.
    GROUP_ORDER_TRIGGER_NAMES.forEach { name ->
        connection.prepare("DROP TRIGGER IF EXISTS `$name`").use { it.step() }
    }

    connection.prepare(
        """
        CREATE TABLE IF NOT EXISTS `profile_payload` (
            `guid` TEXT NOT NULL,
            `payloadVersion` INTEGER NOT NULL,
            `content` TEXT NOT NULL,
            `dedupeKey` TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(`guid`)
        )
        """.trimIndent()
    ).use { it.step() }

    connection.prepare(
        """
        CREATE TABLE `profiles_new` (
            `guid` TEXT NOT NULL,
            `sortOrder` INTEGER NOT NULL,
            `groupSortOrder` INTEGER NOT NULL DEFAULT 9223372036854775807,
            `configType` INTEGER NOT NULL,
            `subscriptionId` TEXT NOT NULL,
            `addedTime` INTEGER NOT NULL,
            `remarks` TEXT NOT NULL,
            `description` TEXT,
            `server` TEXT,
            `serverPort` TEXT,
            `network` TEXT,
            `security` TEXT,
            `insecure` INTEGER,
            PRIMARY KEY(`guid`)
        )
        """.trimIndent()
    ).use { it.step() }

    val metadataInsert = connection.prepare(
        """
        INSERT INTO `profiles_new`(
            `guid`, `sortOrder`, `groupSortOrder`, `configType`, `subscriptionId`, `addedTime`,
            `remarks`, `description`, `server`, `serverPort`, `network`, `security`, `insecure`
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    )
    val payloadInsert = connection.prepare(
        """
        INSERT INTO `profile_payload`(`guid`, `payloadVersion`, `content`, `dedupeKey`)
        VALUES (?, ?, ?, ?)
        """.trimIndent()
    )

    try {
        connection.prepare(
            """
            SELECT `guid`, `sortOrder`, `groupSortOrder`, `dedupeKey`, `configVersion`, `configType`,
                   `subscriptionId`, `addedTime`, `remarks`, `description`, `server`, `serverPort`,
                   `password`, `method`, `flow`, `username`, `network`, `headerType`, `host`, `path`,
                   `seed`, `kcpMtu`, `kcpTti`, `quicSecurity`, `quicKey`, `mode`, `serviceName`,
                   `authority`, `xhttpMode`, `xhttpExtra`, `finalMask`, `security`, `sni`, `alpn`,
                   `fingerPrint`, `insecure`, `echConfigList`, `verifyPeerCertByName`, `pinnedCA256`,
                   `publicKey`, `shortId`, `spiderX`, `mldsa65Verify`, `secretKey`, `preSharedKey`,
                   `localAddress`, `reserved`, `mtu`, `obfsPassword`, `portHopping`,
                   `portHoppingInterval`, `pinSHA256`, `bandwidthDown`, `bandwidthUp`,
                   `policyGroupType`, `policyGroupSubscriptionId`, `policyGroupFilter`,
                   `policyGroupTestOutbounds`, `policyGroupFallbackTag`, `proxyChainProfiles`,
                   `browserDialerMode`, `remoteDNS`
              FROM `profiles`
            """.trimIndent()
        ).use { source ->
            while (source.step()) {
                val profile = source.toLegacyProfile()

                metadataInsert.clearBindings()
                metadataInsert.bindText(1, profile.guid)
                metadataInsert.bindLong(2, profile.sortOrder)
                metadataInsert.bindLong(3, profile.groupSortOrder)
                // Keep an unknown published protocol id in storage even though the current
                // domain enum exposes it as CUSTOM until the protocol is understood.
                metadataInsert.bindLong(4, source.getLong(5))
                metadataInsert.bindText(5, profile.subscriptionId)
                metadataInsert.bindLong(6, profile.addedTime)
                metadataInsert.bindText(7, profile.remarks)
                metadataInsert.bindNullableText(8, profile.description)
                metadataInsert.bindNullableText(9, profile.server)
                metadataInsert.bindNullableText(10, profile.serverPort)
                metadataInsert.bindNullableText(11, profile.network)
                metadataInsert.bindNullableText(12, profile.security)
                metadataInsert.bindNullableBoolean(13, profile.insecure)
                metadataInsert.step()

                payloadInsert.clearBindings()
                payloadInsert.bindText(1, profile.guid)
                payloadInsert.bindLong(2, PROFILE_PAYLOAD_VERSION.toLong())
                payloadInsert.bindText(3, ProfilePayloadCodec.encode(profile))
                // The identity algorithm now includes unknown protocol ids. Recompute lazily.
                payloadInsert.bindText(4, "")
                payloadInsert.step()
            }
        }
    } finally {
        metadataInsert.close()
        payloadInsert.close()
    }

    connection.prepare("DROP TABLE `profiles`").use { it.step() }
    connection.prepare("ALTER TABLE `profiles_new` RENAME TO `profiles`").use { it.step() }
    connection.prepare(
        "CREATE INDEX IF NOT EXISTS `index_profiles_subscriptionId_groupSortOrder_sortOrder_guid` " +
            "ON `profiles` (`subscriptionId`, `groupSortOrder`, `sortOrder`, `guid`)"
    ).use { it.step() }
    connection.prepare(
        "CREATE INDEX IF NOT EXISTS `index_profiles_groupSortOrder_sortOrder_guid` " +
            "ON `profiles` (`groupSortOrder`, `sortOrder`, `guid`)"
    ).use { it.step() }
    connection.prepare(
        "CREATE INDEX IF NOT EXISTS `index_profiles_remarks` ON `profiles` (`remarks`)"
    ).use { it.step() }
    connection.prepare(
        "CREATE INDEX IF NOT EXISTS `index_profile_payload_dedupeKey` ON `profile_payload` (`dedupeKey`)"
    ).use { it.step() }
    GROUP_ORDER_TRIGGERS.forEach { sql ->
        connection.prepare(sql).use { it.step() }
    }
}

private fun SQLiteStatement.toLegacyProfile(): ProfileItem = ProfileItem(
    guid = getText(0),
    sortOrder = getLong(1),
    groupSortOrder = getLong(2),
    dedupeKey = getText(3),
    configVersion = getLong(4).toInt(),
    configType = EConfigType.fromInt(getLong(5).toInt()) ?: EConfigType.CUSTOM,
    persistedConfigType = persistedTypeOf(getLong(5).toInt()),
    subscriptionId = getText(6),
    addedTime = getLong(7),
    remarks = getText(8),
    description = nullableText(9),
    server = nullableText(10),
    serverPort = nullableText(11),
    password = nullableText(12),
    method = nullableText(13),
    flow = nullableText(14),
    username = nullableText(15),
    network = nullableText(16),
    headerType = nullableText(17),
    host = nullableText(18),
    path = nullableText(19),
    seed = nullableText(20),
    kcpMtu = nullableInt(21),
    kcpTti = nullableInt(22),
    quicSecurity = nullableText(23),
    quicKey = nullableText(24),
    mode = nullableText(25),
    serviceName = nullableText(26),
    authority = nullableText(27),
    xhttpMode = nullableText(28),
    xhttpExtra = nullableText(29),
    finalMask = nullableText(30),
    security = nullableText(31),
    sni = nullableText(32),
    alpn = nullableText(33),
    fingerPrint = nullableText(34),
    insecure = nullableBoolean(35),
    echConfigList = nullableText(36),
    verifyPeerCertByName = nullableText(37),
    pinnedCA256 = nullableText(38),
    publicKey = nullableText(39),
    shortId = nullableText(40),
    spiderX = nullableText(41),
    mldsa65Verify = nullableText(42),
    secretKey = nullableText(43),
    preSharedKey = nullableText(44),
    localAddress = nullableText(45),
    reserved = nullableText(46),
    mtu = nullableInt(47),
    obfsPassword = nullableText(48),
    portHopping = nullableText(49),
    portHoppingInterval = nullableText(50),
    pinSHA256 = nullableText(51),
    bandwidthDown = nullableText(52),
    bandwidthUp = nullableText(53),
    policyGroupType = nullableText(54),
    policyGroupSubscriptionId = nullableText(55),
    policyGroupFilter = nullableText(56),
    policyGroupTestOutbounds = nullableBoolean(57),
    policyGroupFallbackTag = nullableText(58),
    proxyChainProfiles = nullableText(59),
    browserDialerMode = nullableText(60),
    remoteDNS = nullableText(61),
)

private fun SQLiteStatement.nullableText(index: Int): String? = if (isNull(index)) null else getText(index)

private fun SQLiteStatement.nullableInt(index: Int): Int? = if (isNull(index)) null else getLong(index).toInt()

private fun SQLiteStatement.nullableBoolean(index: Int): Boolean? =
    if (isNull(index)) null else getLong(index) != 0L

private fun SQLiteStatement.bindNullableText(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

private fun SQLiteStatement.bindNullableBoolean(index: Int, value: Boolean?) {
    if (value == null) bindNull(index) else bindLong(index, if (value) 1L else 0L)
}
