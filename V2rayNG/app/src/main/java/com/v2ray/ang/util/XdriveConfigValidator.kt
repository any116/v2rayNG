package com.v2ray.ang.util

import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_GOOGLE_DRIVE
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_LOCAL
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_S3
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_TEMPLATE
import java.net.URI

/** Pure validation helpers shared by the editor and runtime config builder. */
internal object XdriveConfigValidator {

    val services = setOf(
        XDRIVE_SERVICE_LOCAL,
        XDRIVE_SERVICE_GOOGLE_DRIVE,
        XDRIVE_SERVICE_S3,
        XDRIVE_SERVICE_TEMPLATE
    )

    fun usesSecrets(service: String): Boolean = when (service) {
        XDRIVE_SERVICE_GOOGLE_DRIVE, XDRIVE_SERVICE_S3, XDRIVE_SERVICE_TEMPLATE -> true
        else -> false
    }

    /** S3 credentials are positional: a blank required slot must not shift the session token. */
    fun secrets(value: String?, service: String? = null): List<String> {
        val lines = value.orEmpty().lineSequence().map(String::trim).toList()
        return if (service == XDRIVE_SERVICE_S3) lines.dropLastWhile(String::isEmpty)
        else lines.filter(String::isNotEmpty)
    }

    fun isValidS3Secrets(value: String?): Boolean {
        val credentials = secrets(value, XDRIVE_SERVICE_S3)
        return credentials.size in 2..3 && credentials[0].isNotEmpty() && credentials[1].isNotEmpty() &&
            credentials.all(::isValidHeaderValue) && credentials[0].none { it in "/ ,\t" }
    }

    fun isValidS3RemoteFolder(value: String?): Boolean = isValidS3Name(value.orEmpty().removeSuffix("/"))

    /** Mirrors the core's S3 addressing rules without performing network requests. */
    fun isValidS3(value: String?): Boolean {
        val config = JsonUtil.parseString(value) ?: return false
        val endpointValue = config.stringValue("endpoint") ?: return false
        val endpoint = runCatching { URI(endpointValue) }.getOrNull() ?: return false
        if (endpoint.scheme !in setOf("http", "https") || endpoint.host.isNullOrBlank() ||
            endpoint.rawUserInfo != null || endpoint.rawQuery != null || endpoint.rawFragment != null ||
            endpoint.isOpaque
        ) {
            return false
        }
        val region = if (config.has("region")) config.stringValue("region") ?: return false else ""
        if (!isValidHeaderValue(region) || region.any { it in "/, \t" }) return false
        val bucket = config.stringValue("bucket") ?: return false
        if (!isValidS3Name(bucket) || '/' in bucket) return false
        val pathStyle = config.get("pathStyle")?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) return false
            it.asBoolean
        } ?: false
        if (!pathStyle) {
            val host = endpoint.host.removePrefix("[").removeSuffix("]")
            if (Utils.isPureIpAddress(host)) return false
            if (bucket.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '.' } ||
                ".." in bucket || bucket.startsWith('.') || bucket.endsWith('.')
            ) {
                return false
            }
        }
        return true
    }

    private fun JsonObject.stringValue(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun isValidHeaderValue(value: String): Boolean = value.all {
        it == '\t' || (it >= ' ' && it != '\u007f')
    }

    private fun isValidS3Name(value: String): Boolean =
        value.none { it < ' ' || it == '\u007f' || it == '\\' } &&
            value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

    /**
     * Validates the portion of the template schema that XDRIVE requires before runtime use.
     * Full URL, HTTP and RE2 validation remains the core's responsibility.
     */
    fun isValidTemplate(value: String?): Boolean {
        val template = JsonUtil.parseString(value) ?: return false
        val requiredOperations = listOf("put", "get", "delete", "list")
        if (requiredOperations.any { operation ->
                val config = template.get(operation)?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@any true
                val url = config.get("url")
                url == null || !url.isJsonPrimitive || !url.asJsonPrimitive.isString || url.asString.isBlank()
            }
        ) {
            return false
        }

        val namesRegex = template.get("list")?.takeIf { it.isJsonObject }
            ?.asJsonObject?.get("namesRegex") ?: return false
        if (!namesRegex.isJsonPrimitive || !namesRegex.asJsonPrimitive.isString || namesRegex.asString.isBlank()) {
            return false
        }
        return hasCaptureGroup(namesRegex.asString)
    }

    /** XDRIVE uses Go RE2; avoid Java's regex dialect for this lightweight capture check. */
    private fun hasCaptureGroup(pattern: String): Boolean {
        var escaped = false
        var inCharacterClass = false
        var inQuotedLiteral = false
        var index = 0
        while (index < pattern.length) {
            val char = pattern[index]
            if (inQuotedLiteral) {
                if (char == '\\' && index + 1 < pattern.length && pattern[index + 1] == 'E') {
                    inQuotedLiteral = false
                    index += 2
                } else {
                    index++
                }
                continue
            }
            if (escaped) {
                escaped = false
                if (char == 'Q') inQuotedLiteral = true
                index++
                continue
            }
            when {
                char == '\\' -> escaped = true
                char == '[' -> inCharacterClass = true
                char == ']' && inCharacterClass -> inCharacterClass = false
                char == '(' && !inCharacterClass -> {
                    if (pattern.startsWith("(?P<", index) || pattern.startsWith("(?<", index)) return true
                    if (!pattern.startsWith("(?", index)) return true
                }
            }
            index++
        }
        return false
    }
}
