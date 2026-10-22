package com.v2ray.ang.enums

/** Root CA source used by app-level HTTP requests and the Xray core configuration. */
enum class RootCertificateProvider(val value: String) {
    SYSTEM("system"),
    SYSTEM_AND_USER("system_user"),
    MOZILLA("mozilla"),
    CHROME("chrome");

    companion object {
        fun from(value: String?): RootCertificateProvider =
            entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}
