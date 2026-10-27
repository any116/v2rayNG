package com.v2ray.ang.data

import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfileMetadataItem
import com.v2ray.ang.data.entities.ProfilePayloadItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil

internal const val PROFILE_PAYLOAD_VERSION = 1

internal fun persistedTypeOf(rawConfigType: Int): Int? =
    if (EConfigType.fromInt(rawConfigType) == null) rawConfigType else null

/** Codec for the complete protocol object kept outside the list metadata table. */
internal object ProfilePayloadCodec {
    private val knownFields = setOf(
        "guid", "sortOrder", "groupSortOrder", "dedupeKey", "configVersion", "configType",
        "subscriptionId", "addedTime", "remarks", "description", "server", "serverPort",
        "password", "method", "flow", "username", "network", "headerType", "host", "path",
        "seed", "kcpMtu", "kcpTti", "quicSecurity", "quicKey", "mode", "serviceName",
        "authority", "xhttpMode", "xhttpExtra", "masqueHeaders", "masqueWarp", "masqueWarpPrivateKey",
        "masqueWarpPublicKey", "masqueWarpAddress", "remoteFolder", "service", "secrets",
        "template",
        "finalMask", "security", "sni", "alpn",
        "fingerPrint", "insecure", "echConfigList", "verifyPeerCertByName", "pinnedCA256",
        "publicKey", "shortId", "spiderX", "mldsa65Verify", "secretKey", "preSharedKey",
        "localAddress", "reserved", "mtu", "obfsPassword", "portHopping", "portHoppingInterval",
        "pinSHA256", "bandwidthDown", "bandwidthUp", "policyGroupType",
        "policyGroupSubscriptionId", "policyGroupFilter", "policyGroupTestOutbounds",
        "policyGroupFallbackTag", "proxyChainProfiles", "browserDialerMode", "remoteDNS",
        "persistedConfigType"
    )

    private val retiredFields = setOf(
        "segmentBytes", "flushIntervalMs", "pollIntervalMs", "maxPollIntervalMs",
        "sessionTtlSeconds", "concurrency", "eagerWindowMs", "holeTimeoutMs",
    )

    internal val knownFieldNames: Set<String>
        get() = knownFields

    private val metadataOwnedFields = setOf(
        "guid", "sortOrder", "groupSortOrder", "dedupeKey", "subscriptionId", "addedTime",
        "remarks", "description", "server", "serverPort", "network", "security", "insecure",
        "persistedConfigType"
    )

    fun encode(profile: ProfileItem, previousContent: String? = null): String {
        val fresh = JsonUtil.parseString(
            JsonUtil.toJson(profile.copy(dedupeKey = "", persistedConfigType = null))
        )
            ?: error("Failed to encode profile payload")
        metadataOwnedFields.forEach { fresh.remove(it) }
        val previous = JsonUtil.parseString(previousContent)
            ?: return fresh.toString()

        // Keep fields introduced by a newer client. Known fields are replaced, including fields
        // that became null, so an older editor cannot silently resurrect stale values.
        knownFields.forEach { previous.remove(it) }
        retiredFields.forEach { previous.remove(it) }
        fresh.entrySet().forEach { (name, value) -> previous.add(name, value) }
        return previous.toString()
    }

    fun decode(content: String): ProfileItem {
        val json = JsonUtil.parseString(content)
            ?: error("Invalid profile payload")
        check(json.has("configType") && !json.get("configType").isJsonNull) {
            "Invalid profile payload: missing configType"
        }
        return JsonUtil.fromJsonSafe(content, ProfileItem::class.java)
            ?: error("Invalid profile payload")
    }
}

internal fun ProfileItem.toMetadataItem(): ProfileMetadataItem = ProfileMetadataItem(
    guid = guid,
    sortOrder = sortOrder,
    groupSortOrder = groupSortOrder,
    configType = persistedConfigType ?: configType.value,
    subscriptionId = subscriptionId,
    addedTime = addedTime,
    remarks = remarks,
    description = description,
    server = server,
    serverPort = serverPort,
    network = network,
    security = security,
    insecure = insecure,
)

internal fun ProfileItem.toPayloadItem(previousContent: String? = null): ProfilePayloadItem = ProfilePayloadItem(
    guid = guid,
    payloadVersion = PROFILE_PAYLOAD_VERSION,
    content = ProfilePayloadCodec.encode(this, previousContent),
    dedupeKey = dedupeKey,
)

internal fun ProfileMetadataItem.merge(payload: ProfilePayloadItem): ProfileItem {
    check(payload.payloadVersion == PROFILE_PAYLOAD_VERSION) {
        "Unsupported profile payload version ${payload.payloadVersion}"
    }
    val decoded = ProfilePayloadCodec.decode(payload.content)
    return decoded.copy(
        guid = guid,
        sortOrder = sortOrder,
        groupSortOrder = groupSortOrder,
        dedupeKey = payload.dedupeKey,
        configType = EConfigType.fromInt(configType) ?: EConfigType.CUSTOM,
        persistedConfigType = persistedTypeOf(configType),
        subscriptionId = subscriptionId,
        addedTime = addedTime,
        remarks = remarks,
        description = description,
        server = server,
        serverPort = serverPort,
        network = network,
        security = security,
        insecure = insecure,
    )
}

internal fun ProfileMetadataItem.mergeOrNull(payload: ProfilePayloadItem?): ProfileItem? {
    if (payload == null || payload.payloadVersion != PROFILE_PAYLOAD_VERSION) return null
    return runCatching { merge(payload) }.getOrNull()
}
