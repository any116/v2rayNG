package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext.RoutingDomainRule
import com.v2ray.ang.dto.V2rayConfig.DnsBean.ServersBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsServerPolicyTest {
    private val remote = listOf("1.1.1.1", "8.8.8.8")
    private val direct = listOf("223.5.5.5", "223.6.6.6")
    private val region = DnsRegionPolicy(listOf("geosite:ru"), "geoip:ru")

    @Test
    fun earlierProxyRuleStaysAheadOfRegionalDirectAndFallbackGroups() {
        val servers = buildDnsServers(
            listOf(
                RoutingDomainRule(listOf("full:example.ru"), AppConfig.TAG_PROXY),
                RoutingDomainRule(region.domains, AppConfig.TAG_DIRECT)
            ),
            listOf(region), remote, direct
        )
        val policies = servers.filterIsInstance<ServersBean>()

        assertEquals(remote, servers.take(remote.size))
        assertEquals(remote, policies.take(2).map { it.address })
        assertEquals(listOf("full:example.ru"), policies.first().domains)
        assertNull(policies.first().expectIPs)
        assertEquals(direct, policies.drop(2).take(2).map { it.address })
        assertTrue(policies.drop(2).take(2).all { it.expectIPs == listOf("geoip:ru") })
        assertEquals(remote, policies.takeLast(2).map { it.address })
        assertTrue(policies.takeLast(2).all { it.expectIPs == null && it.skipFallback == true })
    }

    @Test
    fun regionalRulesKeepTheirOrderRatherThanBeingGroupedByCountry() {
        val cn = DnsRegionPolicy(
            listOf("geosite:cn", "geosite:category-games@cn"),
            "ext:geoip-only-cn-private.dat:cn"
        )
        val rules = listOf(
            RoutingDomainRule(listOf("geosite:cn"), AppConfig.TAG_DIRECT),
            RoutingDomainRule(listOf("full:example.com"), AppConfig.TAG_PROXY),
            RoutingDomainRule(listOf("geosite:category-games@cn"), AppConfig.TAG_DIRECT)
        )
        val policies = buildDnsServers(rules, listOf(cn), remote.take(1), direct.take(1))
            .filterIsInstance<ServersBean>()

        assertEquals(
            listOf(
                "geosite:cn", "geosite:cn", "full:example.com",
                "geosite:category-games@cn", "geosite:category-games@cn"
            ),
            policies.map { it.domains!!.single() }
        )
    }

    @Test
    fun disablingRegionValidationKeepsOrdinaryDirectPolicies() {
        val policies = buildDnsServers(
            listOf(RoutingDomainRule(region.domains, AppConfig.TAG_DIRECT)),
            emptyList(), remote, direct
        ).filterIsInstance<ServersBean>()

        assertEquals(direct, policies.map { it.address })
        assertTrue(policies.all { it.tag == AppConfig.TAG_DOMESTIC_DNS && it.expectIPs == null })
    }

    @Test
    fun mixedDirectRuleRetainsOrdinaryDomainsWithoutUncheckedRegionalDuplicate() {
        val policies = buildDnsServers(
            listOf(RoutingDomainRule(region.domains + "domain:internal.example", AppConfig.TAG_DIRECT)),
            listOf(region), remote, direct
        ).filterIsInstance<ServersBean>()

        assertEquals(6, policies.size)
        assertTrue(policies.filter { it.tag == AppConfig.TAG_DOMESTIC_DNS && it.expectIPs == null }
            .all { it.domains == listOf("domain:internal.example") })
        assertTrue(policies.filter { it.domains == region.domains && it.tag == AppConfig.TAG_DOMESTIC_DNS }
            .all { it.expectIPs == listOf(region.expectedIp) })
    }

    @Test
    fun blockedAndEmptyRulesDoNotAddResolvers() {
        assertEquals(
            remote,
            buildDnsServers(
                listOf(
                    RoutingDomainRule(region.domains, AppConfig.TAG_BLOCKED),
                    RoutingDomainRule(emptyList(), AppConfig.TAG_DIRECT)
                ),
                listOf(region), remote, direct
            )
        )
    }

    @Test
    fun duplicatePoliciesAreRemovedWithoutMergingDifferentExpectations() {
        val rules = listOf(RoutingDomainRule(region.domains, AppConfig.TAG_DIRECT))
        val policies = buildDnsServers(rules + rules, listOf(region), remote, direct)
            .filterIsInstance<ServersBean>()

        assertEquals(4, policies.size)
        assertEquals(2, policies.count { it.expectIPs != null })
        assertEquals(2, policies.count { it.expectIPs == null })
    }
}
