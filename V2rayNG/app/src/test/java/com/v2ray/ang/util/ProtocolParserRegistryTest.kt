package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProtocolParserRegistryTest {

    @Test
    fun bareAndFullSchemeKeysAreCanonicalizedBeforeDispatch() {
        val registry = ProtocolParserRegistry<String>(mapOf(
            "VMESS" to { it },
            "Ss://" to { it }
        ))

        assertEquals(setOf("vmess://", "ss://"), registry.schemes)
        assertEquals("vmess://AbCdEf==", registry.parse("VmEsS://AbCdEf=="))
        assertEquals("ss://AbCdEf==#Node", registry.parse("SS://AbCdEf==#Node"))
    }

    @Test
    fun dispatchMatchesTheEntireHeaderRatherThanAPrefix() {
        val registry = ProtocolParserRegistry<String>(mapOf("vmess" to { it }))

        assertNull(registry.parse("vmess-extra://payload"))
        assertNull(registry.parse("vmess:payload"))
        assertNull(registry.parse("unknown://payload"))
    }

    @Test
    fun aliasesThatNormalizeToTheSameKeyFailAtRegistration() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolParserRegistry<String>(mapOf("vmess" to { it }, "VMESS://" to { it }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolParserRegistry<String>(mapOf("vmess:/" to { it }))
        }
    }
}
