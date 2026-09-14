package com.v2ray.ang.handler

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.text.TextUtils
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.GEOIP_PRIVATE
import com.v2ray.ang.AppConfig.GEOSITE_PRIVATE
import com.v2ray.ang.AppConfig.TAG_DIRECT
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.data.RoutingDao
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.di.PlatformDependencies
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.enums.VpnInterfaceAddressConfig
import com.v2ray.ang.handler.MmkvManager.decodeAllServerList
import com.v2ray.ang.handler.MmkvManager.decodeServerConfig
import com.v2ray.ang.handler.MmkvManager.decodeSubsList
import com.v2ray.ang.handler.MmkvManager.encodeSubscription
import com.v2ray.ang.handler.MmkvManager.removeSubscription
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.random.Random

object SettingsManager {

    @Volatile
    private var runtimeSocksPort: Int? = null

    private val routingDao: RoutingDao
        get() = PlatformDependencies.routingDao(AngApplication.application)

    // ---------------- routing rulesets ----------------

    /**
     * Seeds the preset rulesets when the table is empty. Idempotent: called from application
     * bootstrap and again at the head of the core startup sequence.
     */
    suspend fun ensureRoutingRulesets(context: Context) {
        if (routingDao.all().isNotEmpty()) return
        val presets = getPresetRoutingRulesets(context) ?: return
        routingDao.replaceAll(presets)
    }

    private fun getPresetRoutingRulesets(
        context: Context,
        type: RoutingType = RoutingType.WHITE
    ): List<RulesetItem>? {
        val assets = Utils.readTextFromAssets(context, type.fileName)
        if (TextUtils.isEmpty(assets)) return null
        return JsonUtil.fromJsonSafe(assets, Array<RulesetItem>::class.java)?.toList()
    }

    suspend fun resetRoutingRulesetsFromPresets(context: Context, type: RoutingType): Boolean {
        val presets = getPresetRoutingRulesets(context, type) ?: return false
        routingDao.replaceAll(presets)
        return true
    }

    suspend fun resetRoutingRulesets(content: String?): Boolean {
        if (content.isNullOrEmpty()) return false
        return try {
            val rulesetList = JsonUtil.fromJsonSafe(content, Array<RulesetItem>::class.java)?.toList()
            if (rulesetList.isNullOrEmpty()) return false
            // replaceAll keeps locked rules, matching the previous resetRoutingRulesetsCommon.
            routingDao.replaceAll(rulesetList)
            true
        } catch (e: Exception) {
            LogUtil.e(ANG_PACKAGE, "Failed to reset routing rulesets", e)
            false
        }
    }

    suspend fun getRoutingRuleset(id: String?): RulesetItem? {
        if (id.isNullOrEmpty()) return null
        return routingDao.find(id)
    }

    /**
     * Inserts or updates one rule. A new rule goes to the top, because rule order is match
     * priority and that is where the previous list based implementation put it.
     */
    suspend fun saveRoutingRuleset(id: String?, ruleset: RulesetItem?) {
        if (ruleset == null) return
        if (ruleset.id.isBlank()) {
            ruleset.id = UUID.randomUUID().toString()
        }
        val targetId = id?.takeIf { it.isNotEmpty() } ?: ruleset.id
        val existing = routingDao.find(targetId)
        if (existing != null) {
            routingDao.upsert(ruleset.copy(id = targetId, sortOrder = existing.sortOrder))
            return
        }
        val head = routingDao.all().firstOrNull()?.sortOrder ?: ProfileItem.SORT_STEP
        routingDao.upsert(ruleset.copy(id = targetId, sortOrder = head - ProfileItem.SORT_STEP))
    }

    suspend fun removeRoutingRuleset(id: String?) {
        if (id.isNullOrEmpty()) return
        routingDao.delete(id)
    }

    /**
     * Suspend because the enabled rules now come from the database. The core startup sequence
     * evaluates this once and carries the result in CoreConfigContext instead of calling it
     * while building the tun interface.
     */
    suspend fun routingRulesetsBypassLan(): Boolean {
        when (Prefs.string(AppConfig.PREF_VPN_BYPASS_LAN) ?: "1") {
            "1" -> return true
            "2" -> return false
        }

        val guid = MmkvManager.getSelectServer() ?: return false
        val config = decodeServerConfig(guid) ?: return false
        if (config.configType == EConfigType.CUSTOM) {
            val raw = MmkvManager.decodeServerRaw(guid) ?: return false
            val v2rayConfig = JsonUtil.fromJsonSafe(raw, V2rayConfig::class.java)
            return v2rayConfig?.routing?.rules
                ?.filter { it.outboundTag == TAG_DIRECT }
                ?.any {
                    it.domain?.contains(GEOSITE_PRIVATE) == true ||
                        it.ip?.contains(GEOIP_PRIVATE) == true
                } == true
        }

        return routingDao.enabled()
            .filter { it.outboundTag == TAG_DIRECT }
            .any {
                it.domain?.contains(GEOSITE_PRIVATE) == true ||
                    it.ip?.contains(GEOIP_PRIVATE) == true
            }
    }

    // ---------------- profiles (still MMKV backed until PR 6/8) ----------------

    fun getServerViaRemarks(remarks: String?): ProfileItem? {
        if (remarks.isNullOrEmpty()) return null
        return decodeAllServerList()
            .mapNotNull { guid -> decodeServerConfig(guid) }
            .firstOrNull { it.remarks == remarks }
    }

    fun getProfileRemarks(excludeConfigTypes: Set<EConfigType> = setOf(EConfigType.CUSTOM)): List<String> {
        return decodeAllServerList()
            .asSequence()
            .mapNotNull { guid -> decodeServerConfig(guid) }
            .filter { profile -> profile.configType !in excludeConfigTypes }
            .map { it.remarks.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    /**
     * Removes a subscription, recreating the default one when nothing is left so that ungrouped
     * profiles still have a home.
     */
    fun removeSubscriptionWithDefault(subid: String) {
        SubscriptionUpdater.cancelOne(subId = subid)
        removeSubscription(subid)

        if (decodeSubsList().isNotEmpty()) return
        encodeSubscription(DEFAULT_SUBSCRIPTION_ID, SubscriptionItem(remarks = "Default"))
    }

    // ---------------- ports ----------------

    fun getSocksPort(): Int {
        val port = if (isDynamicSocksPort()) {
            runtimeSocksPort ?: refreshRuntimeSocksPort()
        } else {
            Utils.parseInt(Prefs.string(AppConfig.PREF_SOCKS_PORT), AppConfig.PORT_SOCKS.toInt())
        }
        return port ?: AppConfig.PORT_SOCKS.toInt()
    }

    @Synchronized
    fun refreshRuntimeSocksPort(): Int? {
        if (isDynamicSocksPort()) {
            runtimeSocksPort = Random.nextInt(10000, 65535)
            return runtimeSocksPort
        }
        return null
    }

    fun getSocksUsername(): String? =
        Prefs.string(AppConfig.PREF_SOCKS_USERNAME)?.trim()?.takeIf { it.isNotEmpty() }

    fun getSocksPassword(): String? =
        Prefs.string(AppConfig.PREF_SOCKS_PASSWORD)?.trim()?.takeIf { it.isNotEmpty() }

    fun getHttpPort(): Int = getSocksPort() + if (Utils.isXray()) 0 else 1

    private fun isDynamicSocksPort(): Boolean =
        Prefs.bool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)

    // ---------------- assets ----------------

    fun initAssets(context: Context, assets: AssetManager) {
        val extFolder = Utils.userAssetPath(context)
        try {
            val geo = arrayOf(
                AppConfig.GEOSITE_DAT,
                AppConfig.GEOIP_DAT,
                AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT
            )
            assets.list("")
                ?.filter { geo.contains(it) }
                ?.filter { !File(extFolder, it).exists() }
                ?.forEach {
                    val target = File(extFolder, it)
                    assets.open(it).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    LogUtil.i(AppConfig.TAG, "Copied from apk assets folder to ${target.absolutePath}")
                }
        } catch (e: Exception) {
            LogUtil.e(ANG_PACKAGE, "asset copy failed", e)
        }
    }

    // ---------------- DNS ----------------

    fun getDomesticDnsServers(): List<String> {
        val domesticDns = Prefs.string(AppConfig.PREF_DOMESTIC_DNS) ?: AppConfig.DNS_DIRECT
        val ret = domesticDns.split(",")
            .filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
        return ret.ifEmpty { listOf(AppConfig.DNS_DIRECT) }
    }

    fun getRemoteDnsServers(): List<String> {
        val remoteDns = Prefs.string(AppConfig.PREF_REMOTE_DNS) ?: AppConfig.DNS_PROXY
        val ret = remoteDns.split(",")
            .filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
        return ret.ifEmpty { listOf(AppConfig.DNS_PROXY) }
    }

    fun getVpnDnsServers(): List<String> {
        val vpnDns = Prefs.string(AppConfig.PREF_VPN_DNS) ?: AppConfig.DNS_VPN
        return vpnDns.split(",").filter { Utils.isPureIpAddress(it) }
    }

    // ---------------- misc preferences ----------------

    fun getDelayTestUrl(second: Boolean = false): String {
        return if (second) {
            AppConfig.DELAY_TEST_URL2
        } else {
            Prefs.string(AppConfig.PREF_DELAY_TEST_URL) ?: AppConfig.DELAY_TEST_URL
        }
    }

    /** Clamped to 1..128; the stored value is user editable text. */
    fun getRealPingConcurrency(): Int {
        val value = Prefs.string(AppConfig.PREF_REAL_PING_CONCURRENCY)?.toIntOrNull() ?: 16
        return value.coerceIn(1, 128)
    }

    fun getCurrentVpnInterfaceAddressConfig(): VpnInterfaceAddressConfig {
        val selectedIndex = Prefs.string(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX, "0")
            ?.toIntOrNull() ?: 0
        return VpnInterfaceAddressConfig.getConfigByIndex(selectedIndex)
    }

    fun getVpnMtu(): Int = Utils.parseInt(Prefs.string(AppConfig.PREF_VPN_MTU), AppConfig.VPN_MTU)

    fun isUsingHevTun(): Boolean = Prefs.bool(AppConfig.PREF_USE_HEV_TUNNEL, true)

    fun isVpnMode(): Boolean {
        val mode = Prefs.string(AppConfig.PREF_MODE)
        return mode == null || mode == VPN
    }

    fun isRootMode(): Boolean = Prefs.bool(AppConfig.PREF_ROOT_MODE_ENABLE, false)

    fun canUseProcessRouting(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (isUsingHevTun()) return false
        return Prefs.bool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
    }
}
