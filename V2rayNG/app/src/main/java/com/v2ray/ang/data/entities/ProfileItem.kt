package com.v2ray.ang.data.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["subscriptionId", "sortOrder"]),
        Index(value = ["dedupeKey"]),
        Index(value = ["remarks"]),
    ]
)
data class ProfileItem(
    @PrimaryKey
    val guid: String = "",

    /** Sparse ordering within a group; drag/insert rewrites a single row. */
    var sortOrder: Long = 0L,

    /**
     * Stable digest of duplicateIdentity() used by SQL dedupe. Empty means "not computed yet";
     * ProfileDao.backfillDedupeKeys fills it lazily. Never set this by hand.
     */
    @ColumnInfo(defaultValue = "")
    var dedupeKey: String = "",

    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = System.currentTimeMillis(),

    var remarks: String = "",
    var description: String? = null,
    var server: String? = null,
    var serverPort: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var kcpMtu: Int? = null,
    var kcpTti: Int? = null,

    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,
    var finalMask: String? = null,

    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var verifyPeerCertByName: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,
    @Deprecated("Use pinnedCA256")
    var pinSHA256: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,
    var policyGroupTestOutbounds: Boolean? = null,
    var policyGroupFallbackTag: String? = null,
    var proxyChainProfiles: String? = null,

    var browserDialerMode: String? = null,
) {

    companion object {
        const val SORT_STEP = 1024L

        /**
         * Bump on ANY change that affects duplicateIdentity() output (new protocol field,
         * renamed field, different JSON writer). Stored in settings as DEDUPE_ALGO_VERSION;
         * a mismatch triggers a full backfill so old and new digests never coexist.
         */
        const val DEDUPE_ALGO_VERSION = 1

        fun create(configType: EConfigType): ProfileItem =
            ProfileItem(guid = Utils.getUuid(), configType = configType)
    }

    fun getServerAddressAndPort(): String {
        if (server.isNullOrEmpty() && configType == EConfigType.CUSTOM) {
            return "${AppConfig.LOOPBACK}:${AppConfig.PORT_SOCKS}"
        }
        return "${Utils.getIpv6Address(server)}:$serverPort"
    }

    /**
     * Identity for "remove duplicate configurations": everything that does not affect the
     * connection is zeroed. guid, sortOrder and dedupeKey MUST be cleared here or two rows can
     * never compare equal. ProfileDaoTest covers each of the three.
     */
    fun duplicateIdentity(): ProfileItem =
        copy(
            guid = "",
            sortOrder = 0L,
            dedupeKey = "",
            configVersion = 0,
            subscriptionId = "",
            addedTime = 0L,
            remarks = "",
            description = null
        )
}
