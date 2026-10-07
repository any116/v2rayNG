package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.dto.V2rayNShareItem
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object V2rayNFmt : FmtBase() {
    /** Cross-entry references need only ID/remark pairs; full share objects stay in the import spool. */
    internal class Accumulator {
        private val remarksById = linkedMapOf<String, String?>()

        fun add(str: String): V2rayNShareItem? = V2rayNFmt.parseShareItem(str)?.takeIf { add(it) }

        fun add(item: V2rayNShareItem): Boolean {
            val id = item.IndexId.orEmpty()
            if (remarksById.containsKey(id)) return false
            remarksById[id] = item.Remarks
            return true
        }

        fun profile(item: V2rayNShareItem, subId: String): ProfileItem = item.toProfileItem().apply {
            val proto = item.ProtoExtraObj
            policyGroupSubscriptionId = if (proto?.SubChildItems == "self") {
                subId
            } else {
                null
            }
            proto?.ChildItems?.takeIf { it.isNotNullEmpty() }?.let { ids ->
                val remarks = ids.splitToSequence(",")
                    .mapNotNull { remarksById[it] }
                    .filter { it.isNotNullEmpty() }
                    .iterator()
                if (remarks.hasNext()) {
                    when (item.ConfigType) {
                        101 -> policyGroupFilter = remarks.asSequence()
                            .joinToString("|", "^(", ")$") { Regex.escape(it) }
                        102 -> proxyChainProfiles = remarks.asSequence().joinToString(",")
                    }
                }
            }
        }
    }

    private fun parseShareItem(str: String): V2rayNShareItem? = try {
        JsonUtil.fromJson(Utils.decode(str.substringAfterLast('/')), V2rayNShareItem::class.java)
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to parse V2rayN share item", e)
        null
    }
}
