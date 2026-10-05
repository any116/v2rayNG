package com.v2ray.ang.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UrlSchemeMergeFragmentTest {

    @Test
    fun appendsHintWhenPayloadHasNoFragment() {
        assertEquals("vmess://abc#MyGroup", mergeFragment("vmess://abc", "MyGroup"))
    }

    @Test
    fun keepsExistingFragment() {
        assertEquals("vmess://abc#Own", mergeFragment("vmess://abc#Own", "MyGroup"))
    }

    @Test
    fun leavesMultiLinePayloadAlone() {
        val batch = "vmess://a\nvmess://b"
        assertEquals(batch, mergeFragment(batch, "MyGroup"))
    }

    @Test
    fun blankHintChangesNothing() {
        assertEquals("vmess://abc", mergeFragment("vmess://abc", "   "))
    }

    @Test
    fun emptyPayloadStaysEmpty() {
        assertEquals("", mergeFragment("", "MyGroup"))
    }
}
