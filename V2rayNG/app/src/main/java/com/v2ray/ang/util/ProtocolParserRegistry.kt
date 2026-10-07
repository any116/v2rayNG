package com.v2ray.ang.util

import java.util.Locale

/**
 * Owns the protocol-key contract: registrations accept a bare scheme or a full header and are
 * stored as lowercase `scheme://`. Dispatch canonicalizes only the header, preserving payload case.
 */
internal class ProtocolParserRegistry<T>(entries: Map<String, (String) -> T?>) {
    private val parsers = buildMap<String, (String) -> T?> {
        entries.forEach { (name, parser) ->
            val scheme = normalizeScheme(name)
            require(!containsKey(scheme)) { "Duplicate protocol parser scheme: $scheme" }
            put(scheme, parser)
        }
    }

    val schemes: Set<String> = parsers.keys

    fun parse(raw: String): T? {
        val text = raw.trim { it.isWhitespace() || it == '\uFEFF' }
        val scheme = schemeOf(text) ?: return null
        return parsers[scheme]?.invoke(scheme + text.substring(scheme.length))
    }

    companion object {
        fun normalizeScheme(raw: String): String {
            val name = raw.trim().removeSuffix("://")
            require(isScheme(name)) { "Invalid protocol parser scheme: $raw" }
            return name.lowercase(Locale.ROOT) + "://"
        }

        fun schemeOf(link: String): String? {
            val separator = link.indexOf("://")
            if (separator <= 0) return null
            val name = link.substring(0, separator)
            return if (isScheme(name)) name.lowercase(Locale.ROOT) + "://" else null
        }

        private fun isScheme(name: String): Boolean = name.isNotEmpty()
            && (name.first() in 'A'..'Z' || name.first() in 'a'..'z')
            && name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+.-" }
    }
}
