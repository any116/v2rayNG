package com.v2ray.ang.util

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
