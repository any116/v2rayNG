package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext.RoutingDomainRule
import com.v2ray.ang.dto.V2rayConfig.DnsBean.ServersBean

/**
 * Keep DNS policy priority in routing-rule order. Hoisting regional groups above earlier proxy
 * rules sends their overlapping domains to direct DNS first. Only equivalent resolvers within
 * a group may race; each checked direct group is followed by its unrestricted remote fallback.
 */
internal fun buildDnsServers(
    rules: List<RoutingDomainRule>,
    regionPolicies: List<DnsRegionPolicy>,
    remoteDns: List<String>,
    domesticDns: List<String>
): ArrayList<Any> {
    val expectedIpByDomain = regionPolicies.flatMap { policy ->
        policy.domains.map { it to policy.expectedIp }
    }.toMap()
    val servers = ArrayList<Any>()
    servers.addAll(remoteDns)

    rules.forEach { rule ->
        val domains = rule.domain.distinct()
        if (domains.isEmpty()) return@forEach
        when (rule.outboundTag) {
            AppConfig.TAG_DIRECT -> {
                domains.groupBy { expectedIpByDomain[it] }.forEach { (expectedIp, groupDomains) ->
                    domesticDns.forEach { address ->
                        servers.add(
                            ServersBean(
                                address = address,
                                domains = groupDomains,
                                expectIPs = expectedIp?.let { listOf(it) },
                                skipFallback = true,
                                tag = AppConfig.TAG_DOMESTIC_DNS
                            )
                        )
                    }
                    if (expectedIp != null) {
                        remoteDns.forEach { address ->
                            servers.add(
                                ServersBean(address = address, domains = groupDomains, skipFallback = true)
                            )
                        }
                    }
                }
            }
            AppConfig.TAG_BLOCKED -> Unit
            else -> remoteDns.forEach { address ->
                servers.add(ServersBean(address = address, domains = domains))
            }
        }
    }

    // Compare full policies, not just addresses: checked and unchecked queries are not equivalent.
    return ArrayList(servers.distinctBy { server ->
        if (server is ServersBean) {
            server.copy(
                domains = server.domains?.distinct()?.sorted(),
                expectIPs = server.expectIPs?.distinct()?.sorted()
            )
        } else {
            server
        }
    })
}
