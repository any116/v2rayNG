package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.RoutingDao
import com.v2ray.ang.data.SubscriptionDao
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * Builds the runtime context for the selected profile, resolving the primary outbound and every
 * routing target up front. All reads are suspend: the caller runs inside the core startup
 * sequence, never on the main thread.
 */
object CoreConfigContextBuilder {

    private val complexTypeValues: List<Int>
        get() = EConfigType.entries.filter { it.isComplexType() }.map { it.value }

    /**
     * Returns null only when the selected profile cannot be loaded.
     */
    suspend fun build(context: Context, guid: String): CoreConfigContext? {
        val profileDao = PlatformDependencies.profileDao(context)
        val config = profileDao.findByGuid(guid) ?: return null

        // CUSTOM is handled entirely by CoreConfigManager.
        if (config.configType == EConfigType.CUSTOM) {
            return CoreConfigContext(context = context, guid = guid, isCustom = true)
        }

        val scope = ConfigScope(context)
        // Loaded once, consumed by both the routing-outbound resolver and the DTO.
        val routingRulesets = scope.routingDao.all()

        val primaryResolvedOutbound = resolveOutbound(scope, AppConfig.TAG_PROXY, config)
            ?: run {
                LogUtil.e(AppConfig.TAG, "Failed to resolve main outbound for '${config.remarks}'")
                return null
            }
        val routingResolvedOutbounds = resolveRoutingOutbounds(scope, routingRulesets)
        val resolvedOutbounds = listOf(primaryResolvedOutbound) + routingResolvedOutbounds
        val fallbackResolvedOutbounds = resolveFallbackOutbounds(scope, resolvedOutbounds)
        val routingDomainRules = collectRoutingDomainRulesForDns(routingRulesets)

        return CoreConfigContext(
            context = context,
            guid = guid,
            resolvedOutbounds = resolvedOutbounds + fallbackResolvedOutbounds,
            routingDomainRules = routingDomainRules,
            routingRulesets = routingRulesets,
        )
    }

    /** Per-build handle on the DAOs, so the private resolvers stay free of Context plumbing. */
    private class ConfigScope(context: Context) {
        val profileDao: ProfileDao = PlatformDependencies.profileDao(context)
        val subscriptionDao: SubscriptionDao = PlatformDependencies.subscriptionDao(context)
        val routingDao: RoutingDao = PlatformDependencies.routingDao(context)
        val complexTypes: List<Int> = complexTypeValues
    }

    /**
     * Resolves one outbound target. CUSTOM yields no entry.
     */
    private suspend fun resolveOutbound(
        scope: ConfigScope,
        tag: String,
        config: ProfileItem,
    ): CoreConfigContext.ResolvedOutbound? {
        if (config.configType == EConfigType.CUSTOM) return null

        val (resolvedProfiles, resolvedType) = when (config.configType) {
            EConfigType.POLICYGROUP -> Pair(
                resolvePolicyGroupProfiles(config, scope),
                CoreResolvedType.POLICYGROUP,
            )

            EConfigType.PROXYCHAIN -> {
                val chain = resolveProxyChainProfiles(config, scope)
                Pair(chain, if (chain.size <= 1) CoreResolvedType.NORMAL else CoreResolvedType.PROXYCHAIN)
            }

            else -> {
                val chain = resolveProxyChainProfilesFromGroup(config, scope)
                Pair(chain, if (chain.size <= 1) CoreResolvedType.NORMAL else CoreResolvedType.PROXYCHAIN)
            }
        }

        return CoreConfigContext.ResolvedOutbound(
            tag = tag,
            profile = config,
            resolvedProfiles = resolvedProfiles,
            resolvedType = resolvedType,
        )
    }

    /**
     * Non-builtin routing targets from enabled rules. Missing or unusable targets are skipped and
     * handled by the fallback logic.
     */
    private suspend fun resolveRoutingOutbounds(
        scope: ConfigScope,
        rulesetItems: List<RulesetItem>,
    ): List<CoreConfigContext.ResolvedOutbound> {
        val resolvedOutbounds = mutableListOf<CoreConfigContext.ResolvedOutbound>()
        val processedTags = mutableSetOf<String>()

        val candidateTags = rulesetItems
            .filter { it.enabled }
            .mapNotNull { it.outboundTag.takeIf { tag -> tag.isNotBlank() } }
            .filter { tag -> tag !in AppConfig.BUILTIN_OUTBOUND_TAGS }
            .distinct()

        for (tag in candidateTags) {
            if (!processedTags.add(tag)) continue
            try {
                val profile = SettingsManager.getServerViaRemarks(tag) ?: run {
                    LogUtil.w(AppConfig.TAG, "Routing tag '$tag' has no matching profile — falling back to proxy at routing time")
                    continue
                }
                val resolvedOutbound = resolveOutbound(scope, tag, profile) ?: run {
                    LogUtil.w(AppConfig.TAG, "Cannot use CUSTOM profile as routing outbound for tag '$tag', skipping")
                    continue
                }
                if (resolvedOutbound.resolvedProfiles.isEmpty()) {
                    LogUtil.w(AppConfig.TAG, "Routing outbound '$tag' resolved to empty list, skipping")
                    continue
                }
                resolvedOutbounds.add(resolvedOutbound)
                LogUtil.d(
                    AppConfig.TAG,
                    "Resolved routing outbound: tag='$tag', type='${resolvedOutbound.resolvedType}', profiles=${resolvedOutbound.resolvedProfiles.size}"
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to resolve routing outbound for tag '$tag', skipping", e)
            }
        }

        return resolvedOutbounds
    }

    private suspend fun resolvePolicyGroupProfiles(config: ProfileItem, scope: ConfigScope): List<ProfileItem> {
        return try {
            // Must pass the policy group's TARGET subscription (policyGroupSubscriptionId),
            // not config.subscriptionId which is where the group node itself lives.
            val targetSubId = config.policyGroupSubscriptionId.orEmpty()
            val filter = config.policyGroupFilter
            scope.profileDao.profilesOfScope(targetSubId, scope.complexTypes)
                .asSequence()
                .filter { it.server.isNotNullEmpty() }
                .filter { Utils.isPureIpAddress(it.server!!) || Utils.isValidUrl(it.server!!) }
                .filter { profile ->
                    if (filter.isNullOrBlank()) {
                        true
                    } else {
                        try {
                            Regex(filter).containsMatchIn(profile.remarks)
                        } catch (_: Exception) {
                            profile.remarks.contains(filter)
                        }
                    }
                }
                .toList()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve policy group profiles for '${config.remarks}'", e)
            listOf(config)
        }
    }

    /**
     * Chain nodes listed explicitly on the profile, in the stored order, reversed so the first
     * hop ends up last.
     */
    private suspend fun resolveProxyChainProfiles(config: ProfileItem, scope: ConfigScope): List<ProfileItem> {
        val chain = config.proxyChainProfiles
        if (chain.isNullOrBlank()) return listOf(config)

        return try {
            val resolved = mutableListOf<ProfileItem>()
            for (remark in chain.split(",")) {
                val profile = SettingsManager.getServerViaRemarks(remark) ?: continue
                if (!profile.server.isNotNullEmpty()) continue
                if (!(Utils.isPureIpAddress(profile.server!!) || Utils.isValidUrl(profile.server!!))) continue
                if (profile.configType.isComplexType()) continue
                resolved.add(profile)
            }
            resolved.reversed()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve proxy chain profiles for '${config.remarks}'", e)
            listOf(config)
        }
    }

    /**
     * Chain nodes taken from the subscription neighbours, in order: next, current, prev.
     */
    private suspend fun resolveProxyChainProfilesFromGroup(config: ProfileItem, scope: ConfigScope): List<ProfileItem> {
        if (config.subscriptionId.isEmpty()) return listOf(config)

        return try {
            val subItem = scope.subscriptionDao.find(config.subscriptionId) ?: return listOf(config)
            val resolved = mutableListOf<ProfileItem>()
            val seen = mutableSetOf(config.guid)

            fun addHop(remarks: String?, role: String, hop: ProfileItem?) {
                if (remarks.isNullOrBlank()) return
                if (hop == null) {
                    LogUtil.w(AppConfig.TAG, "Subscription $role proxy '$remarks' has no matching profile, skipping")
                    return
                }
                if (hop.configType.isComplexType()) {
                    LogUtil.w(AppConfig.TAG, "Subscription $role proxy '$remarks' is a complex type, skipping")
                    return
                }
                if (!seen.add(hop.guid)) {
                    LogUtil.w(AppConfig.TAG, "Subscription $role proxy '$remarks' duplicates a hop, skipping")
                    return
                }
                resolved.add(hop)
            }

            addHop(subItem.nextProfile, "exit", SettingsManager.getServerViaRemarks(subItem.nextProfile))
            resolved.add(config)
            addHop(subItem.prevProfile, "entry", SettingsManager.getServerViaRemarks(subItem.prevProfile))
            resolved
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve proxy chain from group for '${config.remarks}'", e)
            listOf(config)
        }
    }

    /**
     * Enabled domain rules in original order, outbound normalized to proxy / direct / block.
     */
    private fun collectRoutingDomainRulesForDns(
        rulesetItems: List<RulesetItem>,
    ): List<CoreConfigContext.RoutingDomainRule> {
        val result = mutableListOf<CoreConfigContext.RoutingDomainRule>()
        rulesetItems
            .filter { it.enabled }
            .filter { !it.domain.isNullOrEmpty() }
            .forEach { rule ->
                result.add(
                    CoreConfigContext.RoutingDomainRule(
                        domain = rule.domain.orEmpty(),
                        outboundTag = when (rule.outboundTag) {
                            AppConfig.TAG_DIRECT -> AppConfig.TAG_DIRECT
                            AppConfig.TAG_BLOCKED -> AppConfig.TAG_BLOCKED
                            else -> AppConfig.TAG_PROXY
                        }
                    )
                )
            }
        return result
    }

    /**
     * Fallback outbounds of POLICYGROUP nodes. Must not overlap resolved or builtin tags.
     *
     * Suspend work runs in an explicit loop — see the note at the top of the file.
     */
    private suspend fun resolveFallbackOutbounds(
        scope: ConfigScope,
        resolvedOutbounds: List<CoreConfigContext.ResolvedOutbound>,
    ): List<CoreConfigContext.ResolvedOutbound> {
        val candidateTags = resolvedOutbounds
            .asSequence()
            .filter { it.resolvedType == CoreResolvedType.POLICYGROUP }
            .filter {
                BalancerStrategyType.from(it.profile.policyGroupType).supportsObservatory &&
                    it.profile.policyGroupTestOutbounds != false
            }
            .mapNotNull { it.profile.policyGroupFallbackTag }
            .filter { it !in AppConfig.BUILTIN_OUTBOUND_TAGS && resolvedOutbounds.none { ob -> ob.tag == it } }
            .distinct()
            .toList()

        val result = mutableListOf<CoreConfigContext.ResolvedOutbound>()
        for (tag in candidateTags) {
            val profile = SettingsManager.getServerViaRemarks(tag) ?: continue
            if (profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP) {
                continue
            }
            val resolved = resolveOutbound(scope, tag, profile) ?: continue
            result.add(resolved)
        }
        return result
    }
}
