package com.v2ray.ang.ui.server

import androidx.annotation.StringRes
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_GOOGLE_DRIVE
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_LOCAL
import com.v2ray.ang.AppConfig.XDRIVE_SERVICE_TEMPLATE
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.ui.base.BaseText
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils

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
                else -> Unit
            }
        }
        if (configType == EConfigType.TROJAN && form.streamSecurity.isBlank()) {
            errors[ServerField.STREAM_SECURITY] = R.string.server_lab_stream_security
        }
        if (configType == EConfigType.WIREGUARD) {
            if (!isIpList(form.remoteDNS)) {
                errors[ServerField.REMOTE_DNS] = R.string.server_lab_remote_dns
            }
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
