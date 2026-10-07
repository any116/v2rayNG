package com.v2ray.ang.fmt

import com.v2ray.ang.dto.V2rayNShareItem
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class V2rayNFmtTest {

    @Test
    fun forwardReferencesAndDuplicateIdsKeepTheFirstChildRemark() {
        val accumulator = V2rayNFmt.Accumulator()
        val group = item("""
            {"IndexId":"group","ConfigType":101,"Remarks":"Group",
             "ProtoExtraObj":{"ChildItems":"child","SubChildItems":"self"}}
        """)
        val child = item("""{"IndexId":"child","ConfigType":6,"Remarks":"edge.node"}""")
        assertTrue(accumulator.add(group))
        assertTrue(accumulator.add(child))
        assertFalse(accumulator.add(item("""{"IndexId":"child","ConfigType":6,"Remarks":"duplicate"}""")))

        val profiles = listOf(accumulator.profile(group, "subA"), accumulator.profile(child, "subA"))
        assertEquals(2, profiles.size)
        assertEquals("subA", profiles.first().policyGroupSubscriptionId)
        assertEquals("^(${Regex.escape("edge.node")})$", profiles.first().policyGroupFilter)
        assertEquals("edge.node", profiles.last().remarks)
    }

    @Test
    fun proxyChainUsesChildReferenceOrderWhenProfilesAreEmittedLazily() {
        val accumulator = V2rayNFmt.Accumulator()
        val chain = item("""
            {"IndexId":"chain","ConfigType":102,"Remarks":"Chain",
             "ProtoExtraObj":{"ChildItems":"second,first"}}
        """)
        accumulator.add(chain)
        accumulator.add(item("""{"IndexId":"first","ConfigType":6,"Remarks":"First"}"""))
        accumulator.add(item("""{"IndexId":"second","ConfigType":6,"Remarks":"Second"}"""))

        assertEquals("Second,First", accumulator.profile(chain, "subA").proxyChainProfiles)
    }

    @Test
    fun firstIdWithMissingRemarkIsNotOverwrittenByALaterDuplicate() {
        val accumulator = V2rayNFmt.Accumulator()
        val group = item("""
            {"IndexId":"group","ConfigType":101,"ProtoExtraObj":{"ChildItems":"child"}}
        """)
        accumulator.add(group)
        assertTrue(accumulator.add(item("""{"IndexId":"child","ConfigType":6}""")))
        assertFalse(accumulator.add(item("""{"IndexId":"child","ConfigType":6,"Remarks":"Later"}""")))

        assertEquals(null, accumulator.profile(group, "subA").policyGroupFilter)
    }

    private fun item(json: String): V2rayNShareItem = JsonUtil.fromJson(json, V2rayNShareItem::class.java)!!
}
