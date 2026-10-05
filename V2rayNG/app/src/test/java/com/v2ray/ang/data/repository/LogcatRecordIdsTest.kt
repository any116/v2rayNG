package com.v2ray.ang.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class LogcatRecordIdsTest {

    @Test
    fun prependingLogsKeepsExistingIdsAndDistinguishesIdenticalRows() {
        val ids = LogcatRecordIds()
        val before = ids.identify(listOf("error", "error", "connected"), emptyList())
        val after = ids.identify(listOf("new", "error", "error", "connected"), before)

        assertEquals(before, after.drop(1))
        assertEquals(after.size, after.map { it.id }.toSet().size)
    }

    @Test
    fun anotherIdenticalErrorGetsANewIdWithoutRenamingOlderOccurrences() {
        val ids = LogcatRecordIds()
        val before = ids.identify(listOf("error", "error"), emptyList())
        val after = ids.identify(listOf("error", "error", "error"), before)

        assertEquals(before, after.drop(1))
        assertNotEquals(before.first().id, after.first().id)
    }

    @Test
    fun evictingOlderLogsDoesNotRenumberSurvivorsOrReuseClearedIds() {
        val ids = LogcatRecordIds()
        val before = ids.identify(listOf("new", "older"), emptyList())
        val retained = ids.identify(listOf("new"), before)
        val reopened = ids.identify(listOf("new"), emptyList())

        assertEquals(before.take(1), retained)
        assertNotEquals(before.first().id, reopened.first().id)
    }
}
