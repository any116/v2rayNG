package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import androidx.sqlite.SQLiteException
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.SubscriptionDao
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.ProfileRaw
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.dto.SubChainValidation
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.V2rayNFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import java.net.URI
import java.util.Locale

object AngConfigManager {

    private val profileDao: ProfileDao
        get() = PlatformDependencies.profileDao(AngApplication.application)

    private val subscriptionDao: SubscriptionDao
        get() = PlatformDependencies.subscriptionDao(AngApplication.application)

    private val settingsStore: SettingsStore
        get() = PlatformDependencies.settingsStore(AngApplication.application)

    private data class ParsedProfile(
        val profile: ProfileItem,
        val rawConfig: String? = null,
    )

    // Parser mapping for different config types (lazy initialized)
    private val configFmtParsers: Map<String, (String) -> ProfileItem?> by lazy {
        mapOf(
            EConfigType.VMESS.protocolScheme to VmessFmt::parse,
            EConfigType.SHADOWSOCKS.protocolScheme to ShadowsocksFmt::parse,
            EConfigType.SOCKS.protocolScheme to SocksFmt::parse,
            AppConfig.SOCKS4 to SocksFmt::parse,
            AppConfig.SOCKS5 to SocksFmt::parse,
            EConfigType.TROJAN.protocolScheme to TrojanFmt::parse,
            EConfigType.VLESS.protocolScheme to VlessFmt::parse,
            EConfigType.WIREGUARD.protocolScheme to WireguardFmt::parse,
            EConfigType.HYSTERIA2.protocolScheme to Hysteria2Fmt::parse,
            AppConfig.HY2 to Hysteria2Fmt::parse,
        )
    }

    /**
     * Shares the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    suspend fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }

            Utils.setClipboard(context, conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares non-custom configurations to the clipboard.
     *
     * @param context The context.
     * @param serverList The list of server GUIDs.
     * @return The number of configurations shared.
     */
    suspend fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    /**
     * Shares the configuration as a QR code.
     *
     * @param guid The GUID of the configuration.
     * @return The QR code bitmap.
     */
    suspend fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)

        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    /**
     * Shares the full content of the configuration to the clipboard.
     *
     * @param context The context.
     * @param guid The GUID of the configuration.
     * @return The result code.
     */
    suspend fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val result = CoreConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    /**
     * Shares the configuration.
     *
     * @param guid The GUID of the configuration.
     * @return The configuration string.
     */
    private suspend fun shareConfig(guid: String): String {
        try {
            val config = profileDao.findByGuid(guid) ?: return ""

            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                else -> {}
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    /**
     * Imports a batch of configurations.
     *
     * @param server The server string.
     * @param subid The subscription ID; empty means the "All" tab, which is not a real group.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    suspend fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        return try {
            val targetSubId = subid.ifEmpty { AppConfig.DEFAULT_SUBSCRIPTION_ID }
            if (targetSubId == AppConfig.DEFAULT_SUBSCRIPTION_ID) {
                // Forced: profiles are about to be written into this group.
                subscriptionDao.ensureDefaultForced(AppConfig.DEFAULT_SUBSCRIPTION_REMARKS)
            }

            var count = parseBatchConfig(Utils.decode(server), targetSubId, append)
            if (count <= 0) {
                count = parseBatchConfig(server, targetSubId, append)
            }
            if (count <= 0) {
                count = parseCustomConfigServer(server, targetSubId, append)
            }

            var importedSubIds = parseBatchSubscription(server)
            if (importedSubIds.isEmpty()) {
                importedSubIds = parseBatchSubscription(Utils.decode(server))
            }
            // Only fetch what this import just created. updateConfigViaSubAll() re-downloaded
            // every subscription in the table, so pasting a single link refreshed all of them.
            if (importedSubIds.isNotEmpty()) {
                updateConfigViaSubIds(importedSubIds)
            }

            count to importedSubIds.size
        } catch (e: SQLiteException) {
            LogUtil.e(AppConfig.TAG, "Failed to store imported profiles", e)
            0 to 0
        }
    }

    /**
     * Parses a batch of subscriptions.
     *
     * @param servers The servers string.
     * @return The guids of the subscriptions that were actually created.
     */
    private suspend fun parseBatchSubscription(servers: String?): List<String> {
        try {
            if (servers == null) {
                return emptyList()
            }

            val created = mutableListOf<String>()
            servers.lines()
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        importUrlAsSubscription(str)?.let { created.add(it) }
                    }
                }
            return created
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return emptyList()
    }

    /**
     * Imports a URL as a subscription.
     *
     * @param url The URL.
     * @return The new subscription guid, or null when it was a duplicate or unparsable.
     */
    private suspend fun importUrlAsSubscription(url: String): String? {
        val subscriptions = subscriptionDao.all()
        val normalized = normalizeSubUrl(url)
        // Compare normalized forms: scheme and host are case-folded and the fragment is a
        // display name, but the request itself keeps its raw form — "/sub" and "/sub/" are not
        // guaranteed to be the same resource, and path/query encodings carry real meaning.
        if (subscriptions.any { normalizeSubUrl(it.url) == normalized }) {
            return null
        }
        val uri = runCatching { URI(Utils.fixIllegalUrl(url)) }.getOrNull()
        val fragment = uri?.fragment?.trim()?.takeIf { it.isNotEmpty() }
        val guid = Utils.getUuid()
        subscriptionDao.insertAtEnd(
            SubscriptionItem(
                guid = guid,
                remarks = uniqueSubRemarks(fragment, subscriptions),
                url = url,
            )
        )
        return guid
    }

    /**
     * Scheme and host are case-insensitive and the fragment is a display name, so those may be
     * folded or dropped. Everything that belongs to the request keeps its RAW form: the
     * percent-encoded path and query ("a%2Fb" and "a/b" are different resources, and a query
     * containing "a%26b" must not collapse into two parameters), the trailing slash, and any
     * userinfo credentials. Folding those merged genuinely different subscriptions.
     */
    private fun normalizeSubUrl(raw: String): String {
        val text = raw.trim().substringBefore('#')
        val uri = runCatching { URI(Utils.fixIllegalUrl(text)) }.getOrNull() ?: return text
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return text
        val host = uri.host?.lowercase(Locale.ROOT) ?: return text

        val authority = buildString {
            uri.rawUserInfo?.let {
                append(it)
                append('@')
            }
            append(host)
            if (uri.port != -1) {
                append(':')
                append(uri.port)
            }
        }

        return buildString {
            append(scheme)
            append("://")
            append(authority)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let {
                append('?')
                append(it)
            }
        }
    }

    /**
     * A fragment-less link used to always become the literal "import sub", so a second paste
     * produced two indistinguishable rows in the group tab strip. Fall back to
     * "import sub1" / "import sub2" / … and keep an explicit fragment unique the same way.
     */
    private fun uniqueSubRemarks(preferred: String?, existing: List<SubscriptionItem>): String {
        val taken = existing.mapTo(HashSet()) { it.remarks }
        if (preferred != null && preferred !in taken) return preferred
        val base = preferred ?: IMPORT_SUB_REMARKS_PREFIX
        var index = 1
        while ("$base$index" in taken) index++
        return "$base$index"
    }

    /**
     * Parses a batch of configurations.
     *
     * @param servers The servers string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private suspend fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        try {
            if (servers == null) {
                return 0
            }
            val subItem = subscriptionDao.find(subid)

            // Parse all configs first (no I/O during parsing)
            val configs = mutableListOf<ProfileItem>()
            val v2raynLines = mutableListOf<String>()

            servers.lines()
                .distinct()
                .forEach {
                    if (it.startsWith(AppConfig.V2RAYNFMTS, ignoreCase = true)) {
                        v2raynLines.add(it)
                    } else {
                        val config = parseConfig(it, subid, subItem)
                        if (config != null) {
                            configs.add(config)
                        }
                    }
                }

            val v2raynConfigs = V2rayNFmt.parse(v2raynLines, subid)
            val allConfigs = v2raynConfigs + configs

            if (allConfigs.isNotEmpty()) {
                commitProfiles(
                    configs = allConfigs.map(::ParsedProfile),
                    subid = subid,
                    append = append,
                )
            }

            return allConfigs.size
        } catch (e: SQLiteException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch config", e)
        }
        return 0
    }

    /**
     * Commits parsed profiles before removing the profiles they replace.
     *
     * @param configs The parsed profiles to save.
     * @param subid The subscription ID.
     * @param append Whether to append to the existing server list.
     */
    private suspend fun commitProfiles(
        configs: List<ParsedProfile>,
        subid: String,
        append: Boolean,
    ) {
        // Last line of defence: replaceGroup would otherwise write rows into a group that
        // owns no subscriptions row, and nothing in the UI can reach those.
        val targetSubId = subid.ifEmpty { AppConfig.DEFAULT_SUBSCRIPTION_ID }
        if (subscriptionDao.find(targetSubId) == null) {
            subscriptionDao.ensureDefaultForced(AppConfig.DEFAULT_SUBSCRIPTION_REMARKS)
        }

        val profiles = ArrayList<ProfileItem>(configs.size)
        val raws = mutableListOf<ProfileRaw>()

        configs.forEachIndexed { index, parsed ->
            val key = Utils.getUuid()
            profiles += parsed.profile.copy(
                guid = key,
                subscriptionId = targetSubId,
                sortOrder = (index + 1).toLong() * ProfileItem.SORT_STEP,
            )
            parsed.rawConfig?.let { raw -> raws += ProfileRaw(key, raw) }
        }

        profileDao.replaceGroup(
            subscriptionId = targetSubId,
            profiles = profiles,
            raws = raws,
            append = append,
        )

        settingsStore.poke(
            SettingsStore.KEY_SELECTED_SERVER,
            profileDao.selectedGuid(),
        )
    }

    /**
     * Parses a custom configuration server.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private suspend fun parseCustomConfigServer(server: String?, subid: String, append: Boolean): Int {
        if (server == null) {
            return 0
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            try {
                val serverList: Array<Any> =
                    JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()

                if (serverList.isNotEmpty()) {
                    val configs = serverList.map { srv ->
                        val config = CustomFmt.parse(JsonUtil.toJson(srv))
                        config.subscriptionId = subid
                        config.description = generateDescription(config)
                        ParsedProfile(
                            profile = config,
                            rawConfig = JsonUtil.toJsonPretty(srv) ?: "",
                        )
                    }
                    commitProfiles(configs, subid, append)
                    return configs.size
                }
            } catch (e: SQLiteException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
            }

            try {
                // For compatibility
                val config = CustomFmt.parse(server)
                config.subscriptionId = subid
                config.description = generateDescription(config)
                commitProfiles(
                    configs = listOf(ParsedProfile(config, server)),
                    subid = subid,
                    append = append,
                )
                return 1
            } catch (e: SQLiteException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
            }
            return 0
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            try {
                val config = WireguardFmt.parseWireguardConfFile(server)
                config.subscriptionId = subid
                config.description = generateDescription(config)
                commitProfiles(
                    configs = listOf(ParsedProfile(config, server)),
                    subid = subid,
                    append = append,
                )
                return 1
            } catch (e: SQLiteException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
            }
            return 0
        } else {
            return 0
        }
    }

    /**
     * Parses the configuration from a QR code or string.
     * Only parses and returns ProfileItem, does not save.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param subItem The subscription item.
     * @return The parsed ProfileItem or null if parsing fails or filtered out.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }

            val config = configFmtParsers.firstNotNullOfOrNull { (scheme, parser) ->
                if (str.startsWith(scheme)) parser(str) else null
            }

            if (config == null) {
                return null
            }

            // Apply filter
            if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
                val matched = Regex(pattern = subItem?.filter.orEmpty())
                    .containsMatchIn(input = config.remarks)
                if (!matched) return null
            }

            config.subscriptionId = subid
            config.description = generateDescription(config)

            return config
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse config", e)
            return null
        }
    }

    /**
     * Updates the configuration via all subscriptions.
     *
     * @return Detailed result of the subscription update operation.
     */
    suspend fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            val subscriptions = subscriptionDao.all()
            var acc = SubscriptionUpdateResult()
            for (sub in subscriptions) {
                acc += updateConfigViaSub(sub)
            }
            acc
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    /**
     * Updates exactly the given subscriptions and nothing else.
     *
     * Exists so import and the per-row refresh button never widen into "update everything".
     */
    suspend fun updateConfigViaSubIds(subIds: List<String>): SubscriptionUpdateResult {
        return try {
            var acc = SubscriptionUpdateResult()
            subIds.distinct().forEach { id ->
                val item = subscriptionDao.find(id)
                if (item == null) {
                    LogUtil.w(AppConfig.TAG, "updateConfigViaSubIds: no subscription for $id")
                } else {
                    acc += updateConfigViaSub(item)
                }
            }
            acc
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via subscription ids", e)
            SubscriptionUpdateResult()
        }
    }

    /** Single-subscription entry point used by the UI. */
    suspend fun updateConfigViaSubId(subId: String): SubscriptionUpdateResult =
        updateConfigViaSubIds(listOf(subId))

    /**
     * Updates the configuration via a subscription.
     *
     * @param it The subscription item.
     * @return Subscription update result.
     */
    suspend fun updateConfigViaSub(it: SubscriptionItem): SubscriptionUpdateResult {
        try {
            // Check if disabled
            if (!it.enabled) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            // Validate subscription info
            if (TextUtils.isEmpty(it.guid)
                || TextUtils.isEmpty(it.remarks)
                || TextUtils.isEmpty(it.url)
            ) {
                return SubscriptionUpdateResult(skipCount = 1)
            }

            val url = HttpUtil.toIdnUrl(it.url)
            if (!Utils.isValidUrl(url)) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            if (!it.allowInsecureUrl) {
                if (!Utils.isValidSubUrl(url)) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            LogUtil.i(AppConfig.TAG, url)
            val userAgent = it.userAgent
            val requestHeaders = it.requestHeaders
            val proxyUsername = SettingsManager.getSocksUsername()
            val proxyPassword = SettingsManager.getSocksPassword()

            var configText = try {
                val httpPort = SettingsManager.getHttpPort()
                HttpUtil.getUrlContentWithUserAgent(
                    UrlContentRequest(
                        url = url,
                        userAgent = userAgent,
                        requestHeaders = requestHeaders,
                        timeout = 15000,
                        httpPort = httpPort,
                        proxyUsername = proxyUsername,
                        proxyPassword = proxyPassword
                    )
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Update subscription: proxy not ready or other error", e)
                ""
            }
            if (configText.isEmpty()) {
                configText = try {
                    HttpUtil.getUrlContentWithUserAgent(
                        UrlContentRequest(
                            url = url,
                            userAgent = userAgent,
                            requestHeaders = requestHeaders
                        )
                    )
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Update subscription: Failed to get URL content with user agent", e)
                    ""
                }
            }
            if (configText.isEmpty()) {
                return SubscriptionUpdateResult(failureCount = 1)
            }

            val count = parseConfigViaSub(configText, it.guid, false)
            if (count > 0) {
                subscriptionDao.upsert(it.copy(lastUpdated = System.currentTimeMillis()))
                LogUtil.i(AppConfig.TAG, "Subscription updated: ${it.remarks}, $count configs")
                return SubscriptionUpdateResult(
                    configCount = count,
                    successCount = 1
                )
            } else {
                // Got response but no valid configs parsed
                return SubscriptionUpdateResult(failureCount = 1)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    /**
     * Validates a subscription's entry / exit chain references.
     *
     * Both are remarks, so they can name a profile that does not exist, name the same profile
     * twice (which would make the node dial through itself), or name a CUSTOM / POLICYGROUP /
     * PROXYCHAIN entry, which cannot be a chain hop at all.
     */
    suspend fun validateSubChainProfiles(
        prevProfile: String?,
        nextProfile: String?,
    ): SubChainValidation {
        val prev = prevProfile?.trim().orEmpty()
        val next = nextProfile?.trim().orEmpty()
        val missing = mutableListOf<String>()
        listOf(prev, next)
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { remarks ->
                val profile = profileDao.findByRemarks(remarks)
                if (profile == null || profile.configType.isComplexType()) {
                    missing.add(remarks)
                }
            }
        return SubChainValidation(
            missingProfiles = missing,
            selfReference = prev.isNotEmpty() && prev == next,
        )
    }

    /**
     * Removes invalid server configurations for a subscription.
     *
     * @param subId The subscription ID.
     */
    suspend fun removeInvalidServer(subId: String) {
        val targets = profileDao.invalidGuids(subId, "")
        if (targets.isEmpty()) return
        profileDao.deleteProfiles(targets)
        settingsStore.poke(
            SettingsStore.KEY_SELECTED_SERVER,
            profileDao.selectedGuid(),
        )
    }

    /**
     * Sorts servers by test results for a subscription.
     *
     * @param subId The subscription ID.
     */
    suspend fun sortByTestResultsForSub(subId: String) {
        profileDao.sortByDelay(subId)
    }

    /**
     * Parses the configuration via a subscription.
     *
     * @param server The server string.
     * @param subid The subscription ID.
     * @param append Whether to append the configurations.
     * @return The number of configurations parsed.
     */
    private suspend fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid, append)
        }
        return count
    }

    /**
     * Builds the masked display string for a server address and port.
     *
     * @param server The server address, may be null or blank.
     * @param port The server port, may be null or blank.
     * @param prefixLimit Maximum length of the address prefix before the last separator.
     * @return The masked description, or "" when both address and port are blank.
     */
    fun generateDescription(
        server: String?,
        port: String?,
        prefixLimit: Int = 21,
    ): String {
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""

        val isIPv6 = server?.contains(":") == true
        val separator = if (isIPv6) ":" else "."

        val addrPart = server?.let {
            if (isIPv6) {
                it.split(":").take(2).joinToString(":", postfix = ":***")
            } else {
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
            }
        } ?: ""

        val truncatedAddr = truncateByLastSeparator(addrPart, separator, prefixLimit)

        return "$truncatedAddr : ${port ?: ""}"
    }

    fun generateDescription(
        profile: ProfileItem,
        prefixLimit: Int = 21,
    ): String = generateDescription(profile.server, profile.serverPort, prefixLimit)

    private fun truncateByLastSeparator(
        addr: String,
        separator: String,
        limit: Int
    ): String {
        val lastSepIdx = addr.lastIndexOf(separator)
        if (lastSepIdx <= 0) return addr

        val prefix = addr.substring(0, lastSepIdx)
        if (prefix.length <= limit) return addr

        val keepLen = 17
        val truncatedPrefix = prefix.substring(0, keepLen.coerceAtMost(prefix.length)) + "***"
        return truncatedPrefix + addr.substring(lastSepIdx)
    }

    private const val IMPORT_SUB_REMARKS_PREFIX = "import sub"
}
