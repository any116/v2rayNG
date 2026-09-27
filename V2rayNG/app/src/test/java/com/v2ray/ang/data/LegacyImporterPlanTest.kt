package com.v2ray.ang.data

import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

internal class LegacyImporterPlanTest {

    @Test
    fun planCollectsEveryImportedPrimaryKey() {
        val plan = LegacyImporter.plan(legacySnapshot())

        assertEquals(setOf("d1", "d2", "a1", "a2", "a3", "o1"), plan.keysOf("profiles").toSet())
        assertEquals(setOf("a1", "a2"), plan.keysOf("profile_stats").toSet())
        assertEquals(setOf("d1"), plan.keysOf("profile_raw").toSet())
        assertEquals(
            setOf(AppConfig.DEFAULT_SUBSCRIPTION_ID, "subA", "subEmpty"),
            plan.keysOf("subscriptions").toSet()
        )
        assertEquals(setOf("asset1"), plan.keysOf("assets").toSet())
        assertEquals(setOf("r1", "r2"), plan.keysOf("routing_rules").toSet())
        // Five fixture settings plus the dedupe algorithm version written by the plan.
        assertEquals(6, plan.keysOf("settings").size)
    }

    @Test
    fun everyCountedTableIsPopulatedByThePlan() {
        val plan = LegacyImporter.plan(legacySnapshot())

        IMPORTED_TABLES.forEach { table ->
            assertFalse(
                "no keys planned for ${table.table}: keysOf() is out of sync with IMPORTED_TABLES",
                plan.keysOf(table.table).isEmpty()
            )
        }
    }

    @Test
    fun keysOfUnknownTableIsEmpty() {
        val plan = LegacyImporter.plan(legacySnapshot())

        assertEquals(0, plan.keysOf("does_not_exist").size)
    }
}
