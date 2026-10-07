package com.v2ray.ang.data

import androidx.room3.migration.Migration

/** Adds the Xray WireGuard options without rewriting existing profile rows. */
val MIGRATION_1_2 = Migration(1, 2) { connection ->
    connection.prepare("ALTER TABLE `profiles` ADD COLUMN `remoteDNS` TEXT").use { it.step() }
}
