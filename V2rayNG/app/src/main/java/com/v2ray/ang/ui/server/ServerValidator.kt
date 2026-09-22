package com.v2ray.ang.ui.server

import androidx.annotation.StringRes
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.BaseText
import com.v2ray.ang.util.JsonUtil

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
        return errors
    }

    /** Protocol-level checks that don't map cleanly to a single editor field. */
    fun validateProfile(configType: EConfigType, profile: ProfileItem): BaseText? {
        if (configType == EConfigType.TROJAN && profile.security.isNullOrBlank()) {
            return BaseText.of(R.string.server_lab_stream_security)
        }
        if (!profile.xhttpExtra.isNullOrBlank() && JsonUtil.parseString(profile.xhttpExtra) == null) {
            return BaseText.of(R.string.server_lab_xhttp_extra)
        }
        if (!profile.finalMask.isNullOrBlank() && JsonUtil.parseString(profile.finalMask) == null) {
            return BaseText.of(R.string.server_lab_final_mask)
        }
        return null
    }
}
