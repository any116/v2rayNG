package com.v2ray.ang.dto

import com.v2ray.ang.data.entities.ProfileItem

/** A single staged import entry; raw CUSTOM content stays with its profile during batched replay. */
data class ProfileImportRecord(val profile: ProfileItem, val rawConfig: String? = null)
