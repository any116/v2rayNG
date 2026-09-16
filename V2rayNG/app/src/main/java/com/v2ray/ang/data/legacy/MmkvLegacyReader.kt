package com.v2ray.ang.data.legacy

import android.content.Context
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVRecoverStrategic
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.data.SettingsStore
import com.v2ray.ang.data.entities.AssetUrlItem
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.data.entities.SettingsEntry
import com.v2ray.ang.data.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Immutable view of the legacy MMKV stores, ready to be inserted.
 */
data class LegacySnapshot(
    val groups: Map<String, List<Pair<String, ProfileItem>>>,
    val subOrder: List<String>,
    val subscriptions: Map<String, SubscriptionItem>,
    val stats: Map<String, Long>,
    val raws: Map<String, String>,
    val assets: List<AssetUrlItem>,
    val rulesets: List<RulesetItem>,
    val settings: List<SettingsEntry>,
) {
    companion object {
        val EMPTY = LegacySnapshot(
            groups = emptyMap(),
            subOrder = emptyList(),
            subscriptions = emptyMap(),
            stats = emptyMap(),
            raws = emptyMap(),
            assets = emptyList(),
            rulesets = emptyList(),
            settings = emptyList(),
        )
    }

    val isEmpty: Boolean
        get() = groups.isEmpty() && subscriptions.isEmpty() && assets.isEmpty() &&
                rulesets.isEmpty() && settings.isEmpty()
}

/**
 * Raised when the legacy MMKV store exists on disk but cannot be read.
 *
 * Must be distinguished from "there is no legacy data": the first must abort the database
 * create transaction so the upgrade can be retried, the second is a legitimate empty snapshot
 * for a fresh install.
 */
class LegacyReadException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Read-only reader for the legacy MMKV stores. This is the only file that is allowed to import
 * com.tencent.mmkv once MmkvManager is gone.
 *
 * Two consumers:
 *   1. first-time database creation (AppDatabase LegacyImportCallback)
 *   2. restoring an old backup archive that carries an MMKV directory but no v2rayng.db
 *
 * readAll() runs the in-memory equivalent of the two historical migrations BEFORE producing the
 * snapshot, because their inputs (KEY_ANG_CONFIGS, the old pinSHA256 field) live on the MMKV
 * side while this reader consumes their RESULT. Upgrading directly from a very old version
 * would otherwise drop every profile silently.
 *
 * MMKV.initialize() is performed here on first use rather than in Application.onCreate: the
 * legacy store is only touched by this reader, and initializing it in every process would
 * mmap the legacy files four times over for no benefit.
 *
 * @param rootDir MMKV root. Null uses the app-private legacy directory; the restore path
 *                points it at the unpacked archive.
 */
class MmkvLegacyReader(
    private val context: Context,
    private val rootDir: String? = null,
) {

    /**
     * True when the legacy store actually exists on disk. A fresh install has no such file,
     * and readAll() must be allowed to return EMPTY for it without tripping the exception
     * below.
     */
    fun hasLegacyStore(): Boolean =
        if (rootDir != null) File(rootDir, ID_MAIN).isFile
        else context.filesDir.resolve(LEGACY_DIR).resolve(ID_MAIN).isFile

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(INIT_LOCK) {
            if (initialized) return
            MMKV.initialize(
                context,
                context.filesDir.resolve(LEGACY_DIR).absolutePath,
                null,
                if (BuildConfig.DEBUG) MMKVLogLevel.LevelDebug else MMKVLogLevel.LevelInfo,
                RECOVERY_HANDLER,
            )
            initialized = true
        }
    }

    private fun store(id: String): MMKV? {
        ensureInitialized()
        return runCatching {
            if (rootDir == null) {
                MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE)
            } else {
                MMKV.mmkvWithID(id, MMKV.SINGLE_PROCESS_MODE, null, rootDir)
            }
        }.onFailure { LogUtil.e(AppConfig.TAG, "Cannot open legacy store $id", it) }.getOrNull()
    }

    fun readAll(): LegacySnapshot {
        if (!hasLegacyStore()) return LegacySnapshot.EMPTY

        // The MAIN file is present but cannot be opened: the legacy data is real and this
        // read failed. Returning EMPTY here is how the previous version silently dropped
        // every profile on upgrade; throwing lets the create transaction roll back so the
        // import is retried on the next launch.
        val main = store(ID_MAIN)
            ?: throw LegacyReadException("Legacy MAIN store exists on disk but cannot be opened")

        val profiles = store(ID_PROFILE_FULL_CONFIG)
        val subs = store(ID_SUB)
        val aff = store(ID_SERVER_AFF)
        val rawStore = store(ID_SERVER_RAW)
        val assetStore = store(ID_ASSET)
        val settingStore = store(ID_SETTING)

        val subOrder = readSubOrder(main, subs)
        val subscriptions = readSubscriptions(subs, subOrder)

        val groups = readGroups(main, profiles, subOrder)
        val pinFixed = applyHysteria2PinMigration(groups, settingStore)
        val merged = applyServerListMigration(main, profiles, pinFixed, settingStore)

        val liveGuids = merged.values.flatten().mapTo(HashSet()) { it.first }

        return LegacySnapshot(
            groups = merged,
            subOrder = subOrder,
            subscriptions = subscriptions,
            stats = readStats(aff, liveGuids),
            raws = readRaws(rawStore, liveGuids),
            assets = readAssets(assetStore),
            rulesets = readRulesets(settingStore),
            settings = readSettings(main, settingStore),
        )
    }

    // ---- subscriptions ----

    /** Mirrors initSubsList(): an empty SUB_IDS falls back to every key of the SUB store. */
    private fun readSubOrder(main: MMKV, subs: MMKV?): List<String> {
        val stored = main.decodeString(KEY_SUB_IDS)
            ?.let { JsonUtil.fromJsonSafe(it, Array<String>::class.java)?.toList() }
            .orEmpty()
        val order = stored.ifEmpty { subs?.allKeys()?.toList().orEmpty() }
        return order.distinct().filter { it.isNotBlank() }
    }

    private fun readSubscriptions(subs: MMKV?, order: List<String>): Map<String, SubscriptionItem> {
        if (subs == null) return emptyMap()
        val keys = (order + subs.allKeys()?.toList().orEmpty()).distinct()
        return keys.mapNotNull { key ->
            val json = subs.decodeString(key) ?: return@mapNotNull null
            val item = JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java) ?: return@mapNotNull null
            key to item.copy(guid = key)
        }.toMap()
    }

    // ---- profiles ----

    private fun readGroups(
        main: MMKV,
        profiles: MMKV?,
        subOrder: List<String>,
    ): MutableMap<String, MutableList<Pair<String, ProfileItem>>> {
        val out = LinkedHashMap<String, MutableList<Pair<String, ProfileItem>>>()
        if (profiles == null) return out

        val groupIds = buildList {
            add(AppConfig.DEFAULT_SUBSCRIPTION_ID)
            addAll(subOrder)
            main.allKeys()?.forEach { key ->
                if (key.startsWith(KEY_SUB_SERVER_PREFIX)) add(key.removePrefix(KEY_SUB_SERVER_PREFIX))
            }
        }.distinct()

        groupIds.forEach { subId ->
            val json = main.decodeString(KEY_SUB_SERVER_PREFIX + subId) ?: return@forEach
            val guids = JsonUtil.fromJsonSafe(json, Array<String>::class.java) ?: return@forEach
            val rows = guids.distinct().mapNotNull { guid -> decodeProfile(profiles, guid) }
            if (rows.isNotEmpty()) out[subId] = rows.toMutableList()
        }
        return out
    }

    /** Missing payload means an orphan index entry; skipping it matches decodeAllServerList(). */
    private fun decodeProfile(profiles: MMKV, guid: String): Pair<String, ProfileItem>? {
        if (guid.isBlank()) return null
        val json = profiles.decodeString(guid)
        if (json.isNullOrBlank()) return null
        val item = JsonUtil.fromJsonSafe(json, ProfileItem::class.java) ?: return null
        // Gson constructs ProfileItem through Unsafe, so a legacy payload that predates a
        // non-null field leaves that field null at runtime. Normalise the fields added after
        // the JSON was written, otherwise the compiler-inserted null check inside copy() throws.
        if (item.dedupeKey == null) item.dedupeKey = ""
        return guid to item.copy(guid = guid)
    }

    // ---- historical migration 1: pinSHA256 -> pinnedCA256 ----

    @Suppress("DEPRECATION")
    private fun applyHysteria2PinMigration(
        groups: MutableMap<String, MutableList<Pair<String, ProfileItem>>>,
        settings: MMKV?,
    ): MutableMap<String, MutableList<Pair<String, ProfileItem>>> {
        if (settings?.decodeBool(KEY_MIGRATED_HY2_PIN, false) == true) return groups
        groups.forEach { (_, rows) ->
            rows.forEachIndexed { index, (guid, profile) ->
                if (profile.configType != EConfigType.HYSTERIA2) return@forEachIndexed
                if (profile.pinSHA256.isNullOrEmpty() || !profile.pinnedCA256.isNullOrEmpty()) {
                    return@forEachIndexed
                }
                rows[index] = guid to profile.copy(
                    pinnedCA256 = profile.pinSHA256,
                    pinSHA256 = null,
                )
            }
        }
        return groups
    }

    // ---- historical migration 2: KEY_ANG_CONFIGS -> per-subscription indexes ----

    private fun applyServerListMigration(
        main: MMKV,
        profiles: MMKV?,
        groups: MutableMap<String, MutableList<Pair<String, ProfileItem>>>,
        settings: MMKV?,
    ): Map<String, List<Pair<String, ProfileItem>>> {
        if (settings?.decodeBool(KEY_MIGRATED_SERVER_LIST, false) == true) return groups
        if (profiles == null) return groups

        val legacy = main.decodeString(KEY_ANG_CONFIGS) ?: return groups
        val guids = JsonUtil.fromJsonSafe(legacy, Array<String>::class.java) ?: return groups

        val known = groups.values.flatten().mapTo(HashSet()) { it.first }
        guids.forEach { guid ->
            if (guid in known) return@forEach
            val row = decodeProfile(profiles, guid) ?: return@forEach
            val subId = row.second.subscriptionId.ifEmpty { AppConfig.DEFAULT_SUBSCRIPTION_ID }
            groups.getOrPut(subId) { mutableListOf() }.add(row)
            known.add(guid)
        }
        return groups
    }

    // ---- side tables ----

    private fun readStats(aff: MMKV?, live: Set<String>): Map<String, Long> {
        if (aff == null) return emptyMap()
        return aff.allKeys().orEmpty().mapNotNull { guid ->
            if (guid !in live) return@mapNotNull null
            val json = aff.decodeString(guid) ?: return@mapNotNull null
            val item = JsonUtil.fromJsonSafe(json, ServerAffiliationJson::class.java) ?: return@mapNotNull null
            guid to item.testDelayMillis
        }.toMap()
    }

    private fun readRaws(raw: MMKV?, live: Set<String>): Map<String, String> {
        if (raw == null) return emptyMap()
        return raw.allKeys().orEmpty().mapNotNull { guid ->
            if (guid !in live) return@mapNotNull null
            raw.decodeString(guid)?.takeIf { it.isNotBlank() }?.let { guid to it }
        }.toMap()
    }

    private fun readAssets(assets: MMKV?): List<AssetUrlItem> {
        if (assets == null) return emptyList()
        return assets.allKeys().orEmpty().mapNotNull { key ->
            val json = assets.decodeString(key) ?: return@mapNotNull null
            JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java)?.copy(guid = key)
        }.sortedBy { it.addedTime }
    }

    private fun readRulesets(settings: MMKV?): List<RulesetItem> {
        val json = settings?.decodeString(AppConfig.PREF_ROUTING_RULESET) ?: return emptyList()
        if (json.isBlank()) return emptyList()
        return JsonUtil.fromJsonSafe(json, Array<RulesetItem>::class.java)?.toList().orEmpty()
    }

    // ---- scalar preferences ----

    /**
     * MMKV exposes keys but not their value types, so decoding needs an explicit key -> kind
     * registry (see SettingKinds). Unknown keys fall back to decodeString and are skipped when
     * that yields nothing, which is the safe direction: a preference that fails to migrate falls
     * back to its coded default instead of being imported as garbage.
     */
    private fun readSettings(main: MMKV, settings: MMKV?): List<SettingsEntry> {
        val out = mutableListOf<SettingsEntry>()

        settings?.allKeys()?.forEach { key ->
            if (key in SKIPPED_SETTING_KEYS) return@forEach
            val entry = when (SettingKinds.of(key)) {
                SettingsStore.KIND_BOOL ->
                    SettingsEntry(key, settings.decodeBool(key, false).toString(), SettingsStore.KIND_BOOL)

                SettingsStore.KIND_INT ->
                    SettingsEntry(key, settings.decodeInt(key, 0).toString(), SettingsStore.KIND_INT)

                SettingsStore.KIND_LONG ->
                    SettingsEntry(key, settings.decodeLong(key, 0L).toString(), SettingsStore.KIND_LONG)

                SettingsStore.KIND_FLOAT ->
                    SettingsEntry(key, settings.decodeFloat(key, 0f).toString(), SettingsStore.KIND_FLOAT)

                SettingsStore.KIND_SET -> settings.decodeStringSet(key)
                    ?.let { SettingsEntry(key, JsonUtil.toJson(it.toList()), SettingsStore.KIND_SET) }

                else -> settings.decodeString(key)
                    ?.let { SettingsEntry(key, it, SettingsStore.KIND_STRING) }
            }
            entry?.let(out::add)
        }

        main.decodeString(KEY_SELECTED_SERVER)?.takeIf { it.isNotBlank() }?.let {
            out += SettingsEntry(SettingsStore.KEY_SELECTED_SERVER, it, SettingsStore.KIND_STRING)
        }
        main.decodeString(KEY_WEBDAV_CONFIG)?.takeIf { it.isNotBlank() }?.let {
            out += SettingsEntry(SettingsStore.KEY_WEBDAV_CONFIG, it, SettingsStore.KIND_STRING)
        }
        return out
    }

    /** Local mirror of the MMKV JSON shape; the entity now carries a guid the JSON does not. */
    private data class ServerAffiliationJson(val testDelayMillis: Long = 0L)

    private companion object {
        const val LEGACY_DIR = "mmkv"

        const val ID_MAIN = "MAIN"
        const val ID_PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
        const val ID_SERVER_RAW = "SERVER_RAW"
        const val ID_SERVER_AFF = "SERVER_AFF"
        const val ID_SUB = "SUB"
        const val ID_ASSET = "ASSET"
        const val ID_SETTING = "SETTING"

        const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
        const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
        const val KEY_SUB_SERVER_PREFIX = "SUB_SERVERS_"
        const val KEY_SUB_IDS = "SUB_IDS"
        const val KEY_WEBDAV_CONFIG = "WEBDAV_CONFIG"

        const val KEY_MIGRATED_SERVER_LIST = "server_list_to_subscriptions_migrated"
        const val KEY_MIGRATED_HY2_PIN = "hysteria2_pin_sha256_migrated"

        /** Rulesets get their own table; the two markers die with MMKV (user_version replaces them). */
        val SKIPPED_SETTING_KEYS = setOf(
            AppConfig.PREF_ROUTING_RULESET,
            KEY_MIGRATED_SERVER_LIST,
            KEY_MIGRATED_HY2_PIN,
        )

        /**
         * Restores the recovery strategy MmkvManager used before the Room migration. Without
         * it a CRC or file-length failure makes mkvWithID return null, readAll() throws
         * LegacyReadException and every profile is left behind on disk.
         */
        val RECOVERY_HANDLER = object : MMKVHandler {
            override fun onMMKVCRCCheckFail(mmapID: String): MMKVRecoverStrategic =
                recoverFromStorageError(mmapID, "CRC check")

            override fun onMMKVFileLengthError(mmapID: String): MMKVRecoverStrategic =
                recoverFromStorageError(mmapID, "file length check")

            override fun wantLogRedirecting(): Boolean = false

            override fun mmkvLog(
                level: MMKVLogLevel,
                file: String,
                line: Int,
                function: String,
                message: String,
            ) = Unit
        }

        private fun recoverFromStorageError(mmapID: String, error: String): MMKVRecoverStrategic {
            LogUtil.e(AppConfig.TAG, "MMKV $error failed for $mmapID; attempting data recovery")
            return MMKVRecoverStrategic.OnErrorRecover
        }

        private val INIT_LOCK = Any()

        @Volatile
        private var initialized = false
    }
}
