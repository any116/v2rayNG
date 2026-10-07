package com.v2ray.ang.enums

/** Downloaded subscription bodies never follow nested subscription URLs recursively. */
enum class ConfigImportSource(val includeSubscriptionUrls: Boolean) {
    USER_INPUT(true),
    SUBSCRIPTION_RESPONSE(false)
}
