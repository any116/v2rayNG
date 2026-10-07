package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.data.ImportBuffer
import com.v2ray.ang.data.ProfileDao
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.SubscriptionDao
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.dto.ProfileImportRecord
import com.v2ray.ang.dto.SubChainValidation
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.V2rayNShareItem
import com.v2ray.ang.enums.ConfigImportSource
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
import com.v2ray.ang.util.ConfigImportContent
import com.v2ray.ang.util.ConfigImportKind
import com.v2ray.ang.util.ConfigImportLineKind
import com.v2ray.ang.util.ConfigImportParser
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ProtocolParserRegistry
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.distinctImportText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.net.URI
import java.util.Locale

object AngConfigManager {

    private const val MAX_WIREGUARD_CONFIG_CHARS = 1_048_576

    private val profileDao: ProfileDao
        get() = PlatformDependencies.profileDao(AngApplication.application)

    private val subscriptionDao: SubscriptionDao
        get() = PlatformDependencies.subscriptionDao(AngApplication.application)

    private val settingsStore: SettingsStore
        get() = PlatformDependencies.settingsStore(AngApplication.application)

    // The registry canonicalizes every key to lowercase scheme:// and rejects ambiguous aliases.
    private val configFmtParsers: ProtocolParserRegistry<ProfileItem> by lazy {
        ProtocolParserRegistry(
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
                AppConfig.HY2 to Hysteria2Fmt::parse
            )
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
     * Content is classified once so plain links never go through an outer Base64 decoder.
     *
     * @param server The server string.
     * @param subid The subscription ID; empty means the "All" tab, which is not a real group.
     * @param append Whether to append the configurations.
     * @return A pair containing the number of configurations and subscriptions imported.
     */
    suspend fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> =
        importConfig({ StringReader(server.orEmpty()) }, subid, append, ConfigImportSource.USER_INPUT)

    /**
     * Parses into disposable spools first, then atomically replays bounded batches into Room.
     * [source] is mandatory: manual input may create subscriptions, whereas downloaded bodies
     * consume profiles/config documents only. URL-only subscription responses import zero nodes.
     */
    suspend fun importConfig(
        openReader: () -> Reader,
        subid: String,
        append: Boolean,
        source: ConfigImportSource
    ): Pair<Int, Int> {
        return try {
            val context = currentCoroutineContext()
            val targetSubId = subid.ifEmpty { AppConfig.DEFAULT_SUBSCRIPTION_ID }
            ConfigImportParser.parse(
                openReader, configFmtParsers.schemes + AppConfig.V2RAYNFMTS,
                checkActive = { context.ensureActive() }
            ).use { content ->
                val cache = AngApplication.application.cacheDir
                ImportBuffer(cache, ProfileImportRecord::class.java).use { profiles ->
                    ImportBuffer(cache, ProfileImportRecord::class.java).use { v2rayn ->
                        ImportBuffer(cache, String::class.java).use { urls ->
                            stageContent(content, targetSubId, profiles, v2rayn, urls, source)
                            val count = commitProfiles(profiles, v2rayn, targetSubId, append)
                            val importedSubIds = urls.openReader().use { reader ->
                                parseBatchSubscription(urls.entries(reader), alreadyDistinct = true)
                            }
                            if (importedSubIds.isNotEmpty()) updateConfigViaSubIds(importedSubIds)
                            count to importedSubIds.size
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import configurations", e)
            0 to 0
        }
    }

    /**
     * Parses a batch of subscriptions.
     * This boundary deduplicates again unless the caller explicitly hands over a sequence already
     * deduplicated by ConfigImportContent.lines().
     *
     * @param servers The subscription URLs separated from profile links by the import parser.
     * @return The guids of the subscriptions that were actually created.
     */
    private suspend fun parseBatchSubscription(
        servers: Sequence<String>,
        alreadyDistinct: Boolean = false
    ): List<String> {
        try {
            val created = mutableListOf<String>()
            val context = currentCoroutineContext()
            val candidates = if (alreadyDistinct) servers else {
                servers.distinctImportText(key = { it }, checkActive = { context.ensureActive() })
            }
            candidates.forEach { str ->
                if (Utils.isValidSubUrl(str)) {
                    importUrlAsSubscription(str)?.let { created.add(it) }
                }
            }
            return created
        } catch (e: CancellationException) {
            throw e
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

    /** Profile links arrive normalized and deduplicated by ConfigImportContent.lines(), including on direct use. */
    private suspend fun stageContent(
        content: ConfigImportContent,
        subid: String,
        profiles: ImportBuffer<ProfileImportRecord>,
        v2rayn: ImportBuffer<ProfileImportRecord>,
        urls: ImportBuffer<String>,
        source: ConfigImportSource
    ) {
        val context = currentCoroutineContext()
        when (content.kind) {
            ConfigImportKind.LINKS -> {
                val filter = subscriptionDao.find(subid)?.filter?.takeIf { it.isNotEmpty() }?.let(::Regex)
                val accumulator = V2rayNFmt.Accumulator()
                ImportBuffer(AngApplication.application.cacheDir, V2rayNShareItem::class.java).use { shares ->
                    for (line in content.lines(source)) {
                        context.ensureActive()
                        when (line.kind) {
                            ConfigImportLineKind.SUBSCRIPTION -> urls.append(line.text)
                            ConfigImportLineKind.PROFILE -> if (line.text.startsWith(AppConfig.V2RAYNFMTS)) {
                                accumulator.add(line.text)?.let(shares::append)
                            } else {
                                parseConfig(line.text, subid, filter)?.let { profiles.append(ProfileImportRecord(it)) }
                            }
                        }
                    }
                    // Resolve forward references from the compact index, replaying one share at a time.
                    shares.openReader().use { reader ->
                        for (item in shares.entries(reader)) {
                            context.ensureActive()
                            val profile = accumulator.profile(item, subid)
                            v2rayn.append(ProfileImportRecord(profile))
                        }
                    }
                }
            }
            ConfigImportKind.JSON -> JsonReader(content.reader).use { reader ->
                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        context.ensureActive()
                        stageCustomJson(JsonParser.parseReader(reader), subid, profiles, normalizeNumbers = true)
                    }
                    reader.endArray()
                } else {
                    stageCustomJson(JsonParser.parseReader(reader), subid, profiles, normalizeNumbers = false)
                }
                if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing configuration content")
            }
            ConfigImportKind.WIREGUARD -> {
                val raw = buildString {
                    var length = 0
                    val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        context.ensureActive()
                        val size = content.reader.read(buffer)
                        if (size < 0) break
                        if (length + size > MAX_WIREGUARD_CONFIG_CHARS) {
                            throw IOException("WireGuard configuration exceeds import limit")
                        }
                        append(buffer, 0, size)
                        length += size
                    }
                }
                if (raw.contains("[Peer]")) {
                    val profile = WireguardFmt.parseWireguardConfFile(raw)
                    profile.subscriptionId = subid
                    profile.description = generateDescription(profile)
                    profiles.append(ProfileImportRecord(profile, raw))
                }
            }
            ConfigImportKind.EMPTY -> Unit
            ConfigImportKind.BASE64 -> error("Base64 must be unwrapped before staging")
        }
    }

    private fun stageCustomJson(
        element: JsonElement,
        subid: String,
        profiles: ImportBuffer<ProfileImportRecord>,
        normalizeNumbers: Boolean
    ) {
        if (!element.isJsonObject) return
        val json = element.asJsonObject
        if (!json.has("inbounds") || !json.has("outbounds") || !json.has("routing")) return
        // Preserve the former JSON-array number normalization, but materialize only one entry.
        val value = if (normalizeNumbers) JsonUtil.fromJson(element.toString(), Any::class.java) else element
        val raw = JsonUtil.toJsonPretty(value) ?: return
        val profile = CustomFmt.parse(raw)
        profile.subscriptionId = subid
        profile.description = generateDescription(profile)
        profiles.append(ProfileImportRecord(profile, raw))
    }

    private suspend fun commitProfiles(
        profiles: ImportBuffer<ProfileImportRecord>,
        v2rayn: ImportBuffer<ProfileImportRecord>,
        subid: String,
        append: Boolean
    ): Int {
        if (profiles.count + v2rayn.count == 0) return 0
        if (subid == AppConfig.DEFAULT_SUBSCRIPTION_ID) {
            subscriptionDao.ensureDefaultForced(AppConfig.DEFAULT_SUBSCRIPTION_REMARKS)
        }
        val count = profiles.openReader().use { normalReader ->
            v2rayn.openReader().use { v2raynReader ->
                val entries = v2rayn.entries(v2raynReader) + profiles.entries(normalReader)
                profileDao.importGroupBatches(subid, entries.chunked(AppConfig.IMPORT_BATCH_SIZE), append)
            }
        }
        settingsStore.poke(SettingsStore.KEY_SELECTED_SERVER, profileDao.selectedGuid())
        return count
    }

    /**
     * Parses the configuration from a QR code or string.
     * Only parses and returns ProfileItem, does not save.
     *
     * @param str The configuration string.
     * @param subid The subscription ID.
     * @param filter The subscription filter compiled once for the entire import.
     * @return The parsed ProfileItem or null if parsing fails or filtered out.
     */
    private fun parseConfig(
        str: String?,
        subid: String,
        filter: Regex?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }

            val config = configFmtParsers.parse(str)

            if (config == null) {
                return null
            }

            // Apply filter
            if (filter != null && config.remarks.isNotNullEmpty()) {
                if (!filter.containsMatchIn(config.remarks)) return null
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
                currentCoroutineContext().ensureActive()
                acc += updateConfigViaSub(sub)
            }
            acc
        } catch (e: CancellationException) {
            throw e
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
                currentCoroutineContext().ensureActive()
                val item = subscriptionDao.find(id)
                if (item == null) {
                    LogUtil.w(AppConfig.TAG, "updateConfigViaSubIds: no subscription for $id")
                } else {
                    acc += updateConfigViaSub(item)
                }
            }
            acc
        } catch (e: CancellationException) {
            throw e
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

            val download = File.createTempFile("subscription-download-", ".txt", AngApplication.application.cacheDir)
            val count = try {
                val context = currentCoroutineContext()
                val directRequest = UrlContentRequest(url = url, userAgent = userAgent, requestHeaders = requestHeaders)
                val proxyRequest = directRequest.copy(
                    timeout = 15000,
                    httpPort = SettingsManager.getHttpPort(),
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
                var downloaded = downloadSubscription(proxyRequest, download) { context.ensureActive() }
                if (downloaded == 0L) {
                    downloaded = downloadSubscription(directRequest, download) { context.ensureActive() }
                }
                if (downloaded == 0L) return SubscriptionUpdateResult(failureCount = 1)
                importConfig(
                    { download.bufferedReader() }, it.guid,
                    append = false, source = ConfigImportSource.SUBSCRIPTION_RESPONSE
                ).first
            } finally {
                runCatching {
                    if (download.exists() && !download.delete()) throw IOException("Failed to delete subscription download")
                }.onFailure { LogUtil.e(AppConfig.TAG, "Failed to clean subscription download", it) }
            }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    private fun downloadSubscription(request: UrlContentRequest, file: File, checkActive: () -> Unit): Long = try {
        HttpUtil.downloadUrlContentWithUserAgent(request, file, checkActive, AppConfig.MAX_IMPORT_CONTENT_CHARS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to download subscription", e)
        0L
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
