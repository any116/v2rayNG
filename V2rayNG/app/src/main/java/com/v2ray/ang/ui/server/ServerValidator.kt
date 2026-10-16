package com.v2ray.ang.ui.server

import androidx.annotation.StringRes
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_GOOGLE_DRIVE
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_LOCAL
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_TEMPLATE
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.TLS
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.ui.base.BaseText
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.net.URI

/**
 * Centralised validation rules for the server configuration screen.
 * Context-free, side-effect-free and unit-testable.
 *
 * Field-level failures come back as a [ServerFieldError] map so the editor can paint
 * inline errors instead of relying on transient toasts. Failures that don't map to a
 * specific field still return a [BaseText] for the toast pipeline.
 */
internal object ServerValidator {

    /** A validation failure attributed to one editor field. */
    data class ServerFieldError(val field: ServerField, @StringRes val messageRes: Int)

    /**
     * Field-level checks. Returns every failing field, so all blank values are flagged
     * in one save attempt instead of one at a time.
     */
    fun validateForm(configType: EConfigType, form: ServerForm): Map<ServerField, Int> {
        val errors = mutableMapOf<ServerField, Int>()
        if (form.remarks.isBlank()) {
            errors[ServerField.REMARKS] = R.string.server_lab_remarks
        }
        if (form.address.isBlank()) {
            errors[ServerField.ADDRESS] = R.string.server_lab_address
        }
        if (configType != EConfigType.HYSTERIA2 && (form.port.toIntOrNull() ?: 0) <= 0) {
            errors[ServerField.PORT] = R.string.server_lab_port
        }
        if (form.password.isBlank()) {
            when (configType) {
                EConfigType.VMESS, EConfigType.VLESS ->
                    errors[ServerField.PASSWORD] = R.string.server_lab_id
                EConfigType.TROJAN, EConfigType.SHADOWSOCKS, EConfigType.HYSTERIA2 ->
                    errors[ServerField.PASSWORD] = R.string.server_lab_id3
                EConfigType.MASQUE -> if (!hasMasqueAuthorization(form.masqueHeaders)) {
                    errors[ServerField.PASSWORD] = R.string.server_lab_masque_password
                }
                else -> Unit
            }
        }
        if (configType == EConfigType.MASQUE && form.password.isNotBlank() && form.username.isBlank()) {
            errors[ServerField.USERNAME] = R.string.server_lab_masque_user
        }
        if (configType == EConfigType.MASQUE && form.username.contains(':')) {
            errors[ServerField.USERNAME] = R.string.server_lab_masque_user_error
        }
        if (configType == EConfigType.TROJAN && form.streamSecurity.isBlank()) {
            errors[ServerField.STREAM_SECURITY] = R.string.server_lab_stream_security
        }
        if (configType == EConfigType.MASQUE && form.streamSecurity.isNotBlank() && form.streamSecurity != TLS) {
            errors[ServerField.STREAM_SECURITY] = R.string.server_lab_masque_tls_error
        }
        if (configType == EConfigType.WIREGUARD || configType == EConfigType.MASQUE) {
            if (!isIpList(form.remoteDNS)) {
                errors[ServerField.REMOTE_DNS] = R.string.server_lab_remote_dns
            }
        }
        if (configType == EConfigType.MASQUE &&
            form.masqueHeaders.isNotBlank() &&
            !isValidMasqueHeaders(form.masqueHeaders)
        ) {
            errors[ServerField.MASQUE_HEADERS] = R.string.server_lab_masque_headers_error
        }
        if (configType == EConfigType.MASQUE &&
            form.password.isNotBlank() &&
            hasMasqueAuthorization(form.masqueHeaders)
        ) {
            errors[ServerField.MASQUE_HEADERS] = R.string.server_lab_masque_auth_error
        }
        if (configType == EConfigType.MASQUE && !isValidMasqueHost(form.host)) {
            errors[ServerField.HOST] = R.string.server_lab_masque_host_error
        }
        if (configType == EConfigType.MASQUE && !isValidMasquePath(form.path)) {
            errors[ServerField.PATH] = R.string.server_lab_masque_path_error
        }
        if (form.network == NetworkType.XDRIVE.type &&
            form.service != XDRIVE_SERVICE_TEMPLATE &&
            form.remoteFolder.isBlank()
        ) {
            errors[ServerField.REMOTE_FOLDER] = R.string.server_lab_xdrive_remote_folder
        }
        if (form.network == NetworkType.XDRIVE.type && form.streamSecurity == REALITY) {
            errors[ServerField.STREAM_SECURITY] = R.string.server_lab_xdrive_reality_error
        }
        return errors
    }

    private fun isIpList(value: String): Boolean {
        if (value.isBlank()) return true
        val entries = value.split(",").map(String::trim)
        if (entries.any(String::isEmpty)) return false
        return entries.all { entry -> Utils.isPureIpAddress(entry) && !entry.startsWith("[") }
    }

    /** Protocol-level checks that don't map cleanly to a single editor field. */
    fun validateProfile(configType: EConfigType, profile: ProfileItem): BaseText? {
        if (configType == EConfigType.TROJAN && profile.security.isNullOrBlank()) {
            return BaseText.of(R.string.server_lab_stream_security)
        }
        if (configType == EConfigType.MASQUE) {
            if (profile.security != TLS) {
                return BaseText.of(R.string.server_lab_masque_tls_error)
            }
            if (profile.password.isNullOrBlank() && !hasMasqueAuthorization(profile.masqueHeaders)) {
                return BaseText.of(R.string.server_lab_masque_password)
            }
            if (!profile.password.isNullOrBlank() && profile.username.isNullOrBlank()) {
                return BaseText.of(R.string.server_lab_masque_user)
            }
            if (profile.username?.contains(':') == true) {
                return BaseText.of(R.string.server_lab_masque_user_error)
            }
            if (!profile.masqueHeaders.isNullOrBlank() && !isValidMasqueHeaders(profile.masqueHeaders)) {
                return BaseText.of(R.string.server_lab_masque_headers_error)
            }
            if (!profile.password.isNullOrBlank() && hasMasqueAuthorization(profile.masqueHeaders)) {
                return BaseText.of(R.string.server_lab_masque_auth_error)
            }
            if (!isValidMasqueHost(profile.host.orEmpty())) {
                return BaseText.of(R.string.server_lab_masque_host_error)
            }
            if (!isValidMasquePath(profile.path.orEmpty())) {
                return BaseText.of(R.string.server_lab_masque_path_error)
            }
        }
        if (profile.network == NetworkType.XDRIVE.type) {
            if (profile.service !in setOf(
                    XDRIVE_SERVICE_LOCAL,
                    XDRIVE_SERVICE_GOOGLE_DRIVE,
                    XDRIVE_SERVICE_TEMPLATE,
                )
            ) {
                return BaseText.of(R.string.server_lab_xdrive_service_error)
            }
            if (profile.service != XDRIVE_SERVICE_TEMPLATE && profile.remoteFolder.isNullOrBlank()) {
                return BaseText.of(R.string.server_lab_xdrive_remote_folder)
            }
            if (profile.security == REALITY) {
                return BaseText.of(R.string.server_lab_xdrive_reality_error)
            }
            if (profile.service == XDRIVE_SERVICE_GOOGLE_DRIVE && xdriveSecrets(profile.secrets).size != 3) {
                return BaseText.of(R.string.server_lab_xdrive_secrets_error)
            }
            if (profile.service == XDRIVE_SERVICE_TEMPLATE && profile.template.isNullOrBlank()) {
                return BaseText.of(R.string.server_lab_xdrive_template)
            }
            if (profile.service == XDRIVE_SERVICE_TEMPLATE && !isValidXdriveTemplate(profile.template)) {
                return BaseText.of(R.string.server_lab_xdrive_template)
            }
        }
        if (!profile.xhttpExtra.isNullOrBlank() && JsonUtil.parseString(profile.xhttpExtra) == null) {
            return BaseText.of(R.string.server_lab_xhttp_extra)
        }
        if (!profile.finalMask.isNullOrBlank() && JsonUtil.parseString(profile.finalMask) == null) {
            return BaseText.of(R.string.server_lab_final_mask)
        }
        return null
    }

    private fun xdriveSecrets(value: String?): List<String> = value.orEmpty()
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

    private fun hasMasqueAuthorization(value: String?): Boolean {
        return hasMasqueHeader(value, "Authorization") { it.isNotBlank() }
    }

    private fun hasMasqueHeader(
        value: String?,
        expectedName: String,
        valuePredicate: (String) -> Boolean = { true },
    ): Boolean {
        val headers = masqueHeaderEntries(value.orEmpty()) ?: return false
        return headers.any { (name, headerValue) ->
            name.equals(expectedName, ignoreCase = true) && valuePredicate(headerValue)
        }
    }

    private fun isValidMasqueHeaders(value: String?): Boolean {
        return masqueHeaderEntries(value)?.all { (name, headerValue) ->
            isValidHeaderName(name) &&
                !name.equals("Host", ignoreCase = true) &&
                !name.equals("Capsule-Protocol", ignoreCase = true) &&
                isValidHeaderValue(headerValue)
        } == true
    }

    private fun masqueHeaderEntries(value: String?): List<Pair<String, String>>? {
        val raw = value.orEmpty().trim()
        if (raw.startsWith("{")) {
            val headers = JsonUtil.parseString(raw) ?: return null
            val entries = mutableListOf<Pair<String, String>>()
            for ((name, headerValue) in headers.entrySet()) {
                if (!headerValue.isJsonPrimitive || !headerValue.asJsonPrimitive.isString) {
                    return null
                }
                entries += name to headerValue.asString
            }
            return entries
        }
        val entries = mutableListOf<Pair<String, String>>()
        for (line in raw.lineSequence().filter(String::isNotBlank)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            entries += line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        return entries
    }

    private fun isValidMasqueHost(value: String): Boolean {
        if (value.isBlank()) return true
        return runCatching {
            val uri = URI("https://$value")
            uri.host != null &&
                uri.rawAuthority == value &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        }.getOrDefault(false)
    }

    private fun isValidMasquePath(value: String): Boolean {
        if (value.isBlank()) return true
        return value.startsWith('/') &&
            value.replace("{target}", "")
                .replace("{ipproto}", "")
                .replace("{?target,ipproto}", "")
                .replace("{?ipproto,target}", "")
                .replace("{&target,ipproto}", "")
                .replace("{&ipproto,target}", "")
                .none { it == '{' || it == '}' } &&
            !value.contains('\r') &&
            !value.contains('\n')
    }

    private fun isValidHeaderName(value: String): Boolean =
        HEADER_NAME_PATTERN.matches(value)

    private fun isValidHeaderValue(value: String): Boolean =
        value.all { it == '\t' || it.code in 0x20..0x7e || it.code >= 0x80 }

    private val HEADER_NAME_PATTERN = Regex("""^[!#$%&'*+.^_`|~0-9A-Za-z-]+$""")

    private fun isValidXdriveTemplate(value: String?): Boolean {
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

    /**
     * XDRIVE evaluates this expression with Go's RE2 implementation. Avoid using Java's regex
     * compiler here because the two dialects accept different syntax; the core remains the
     * authority for full regex validation, while the editor only checks the required capture.
     */
    private fun hasCaptureGroup(pattern: String): Boolean {
        var escaped = false
        pattern.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\') {
                escaped = true
                return@forEachIndexed
            }
            if (char != '(') return@forEachIndexed

            val suffix = pattern.substring(index)
            if (suffix.startsWith("(?P<") || suffix.startsWith("(?<")) return true
            if (suffix.startsWith("(?")) return@forEachIndexed
            return true
        }
        return false
    }
}
