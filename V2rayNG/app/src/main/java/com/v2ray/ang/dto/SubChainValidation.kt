package com.v2ray.ang.dto

/**
 * Result of checking a subscription's entry (prevProfile) / exit (nextProfile) references.
 *
 * Both are stored by remark, not by guid, so nothing in the schema can enforce that they point
 * at a usable profile. This is the check that used to be missing entirely.
 */
data class SubChainValidation(
    val missingProfiles: List<String> = emptyList(),
    val selfReference: Boolean = false,
) {
    val isValid: Boolean get() = missingProfiles.isEmpty() && !selfReference
}
