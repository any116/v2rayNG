package com.v2ray.ang.enums

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RootCertificateProviderTest {

    @Test
    fun fromRecognizesAllStoredValues() {
        RootCertificateProvider.entries.forEach { provider ->
            assertEquals(provider, RootCertificateProvider.from(provider.value))
        }
    }

    @Test
    fun unknownValueFallsBackToSystem() {
        assertEquals(RootCertificateProvider.SYSTEM, RootCertificateProvider.from("invalid"))
        assertEquals(RootCertificateProvider.SYSTEM, RootCertificateProvider.from(null))
    }
}
