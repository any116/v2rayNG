package com.v2ray.ang.data

import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil
import java.security.MessageDigest

/** Single generation point for ProfileItem.dedupeKey. Gson field order is stable, so the digest is reproducible. */
fun ProfileItem.computeDedupeKey(): String {
    val json = JsonUtil.toJson(duplicateIdentity())
    return MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
