package com.v2ray.ang.util

import android.util.Log
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class JsonUtilTest {

    @Test
    fun toJsonPretty_serializesWholeDoublesAsIntegersAndPreservesFractions() {
        val json = JsonUtil.toJsonPretty(
            mapOf(
                "whole" to 42.0,
                "fractional" to 42.75,
            )
        ) ?: error("Expected JSON for a non-null object")
        val objectJson = JsonParser.parseString(json).asJsonObject

        assertEquals("42", objectJson["whole"].toString())
        assertEquals("42.75", objectJson["fractional"].toString())
    }

    @Test
    fun parseHeadersToMap_ignoresJsonNullWithoutLoggingAnError() {
        val logs = Mockito.mockStatic(Log::class.java, Mockito.RETURNS_DEFAULTS)
        try {
            assertEquals(emptyMap<String, String>(), JsonUtil.parseHeadersToMap("null"))

            logs.verify(
                { Log.e(Mockito.anyString(), Mockito.anyString(), Mockito.any(Throwable::class.java)) },
                Mockito.never()
            )
        } finally {
            logs.close()
        }
    }
}
