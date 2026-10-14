package com.v2ray.ang.data

import com.google.gson.JsonPrimitive
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil
import java.security.MessageDigest

/** Single generation point for ProfileItem.dedupeKey. Unknown protocol ids are part of identity. */
fun ProfileItem.computeDedupeKey(): String {
    val identity = duplicateIdentity()
    val jsonObject = JsonUtil.parseString(JsonUtil.toJson(identity))
        ?: error("Failed to encode duplicate identity")
    identity.persistedConfigType?.let { rawType ->
        jsonObject.add("persistedConfigType", JsonPrimitive(rawType))
    }
    val json = jsonObject.toString()
    return MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
