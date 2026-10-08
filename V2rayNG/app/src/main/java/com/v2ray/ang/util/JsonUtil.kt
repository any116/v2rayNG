package com.v2ray.ang.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.v2ray.ang.AppConfig
import java.lang.reflect.Type
import java.math.BigDecimal

object JsonUtil {
    private var gson = Gson()

    /**
     * Converts an object to its JSON representation.
     *
     * @param src The object to convert.
     * @return The JSON representation of the object.
     */
    fun toJson(src: Any?): String {
        return gson.toJson(src)
    }

    /**
     * Parses a JSON string into an object of the specified class.
     *
     * @param src The JSON string to parse.
     * @param cls The class of the object to parse into.
     * @return The parsed object.
     */
    fun <T> fromJson(src: String, cls: Class<T>): T? {
        return gson.fromJson(src, cls)
    }

    /**
     * Safely parses a JSON string into an object of the specified class.
     * Returns null if parsing fails instead of throwing an exception.
     *
     * @param src The JSON string to parse.
     * @param cls The class of the object to parse into.
     * @return The parsed object, or null if parsing fails.
     */
    fun <T> fromJsonSafe(src: String, cls: Class<T>): T? {
        return try {
            gson.fromJson(src, cls)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse JSON", e)
            null
        }
    }

    /**
     * Converts an object to its pretty-printed JSON representation.
     *
     * @param src The object to convert.
     * @return The pretty-printed JSON representation of the object, or null if the object is null.
     */
    fun toJsonPretty(src: Any?): String? {
        if (src == null)
            return null
        val gsonPre = GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter( // Convert whole-valued Doubles to integers; the core rejects `1.0` for integer fields.
                object : TypeToken<Double>() {}.type,
                JsonSerializer { src: Double?, _: Type?, _: JsonSerializationContext? ->
                    if (src == null) {
                        JsonNull.INSTANCE
                    } else if (src % 1.0 == 0.0) {
                        JsonPrimitive(BigDecimal.valueOf(src).toBigInteger())
                    } else {
                        JsonPrimitive(src)
                    }
                }
            )
            .create()
        return gsonPre.toJson(src)
    }

    /**
     * Parses a JSON string into a JsonObject.
     *
     * @param src The JSON string to parse.
     * @return The parsed JsonObject, or null if parsing fails.
     */
    fun parseString(src: String?): JsonObject? {
        if (src == null)
            return null
        try {
            val jsonElement = JsonParser.parseString(src)
            return if (jsonElement.isJsonObject) jsonElement.asJsonObject else null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse JSON string", e)
            return null
        }
    }

    fun parseHeadersToMap(headersJson: String?): Map<String, String> {
        val headerMap = mutableMapOf<String, String>()
        val jsonObject = parseString(headersJson) ?: return headerMap
        try {
            for ((key, jsonElement) in jsonObject.entrySet()) {
                if (key.isNullOrBlank() || jsonElement == null || jsonElement.isJsonNull) {
                    continue
                }
                val value = if (jsonElement.isJsonPrimitive) jsonElement.asString else jsonElement.toString()
                if (value.isNotBlank()) {
                    headerMap[key] = value
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse headers JSON", e)
        }
        return headerMap
    }
}
