package com.v2ray.ang.util

import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_GOOGLE_DRIVE
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_LOCAL
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_TEMPLATE

/** Pure validation helpers shared by the editor and runtime config builder. */
internal object XdriveConfigValidator {

    val services = setOf(
        XDRIVE_SERVICE_LOCAL,
        XDRIVE_SERVICE_GOOGLE_DRIVE,
        XDRIVE_SERVICE_TEMPLATE,
    )

    fun secrets(value: String?): List<String> = value.orEmpty()
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

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
