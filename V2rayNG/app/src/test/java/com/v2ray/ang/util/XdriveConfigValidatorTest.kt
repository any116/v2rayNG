package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XdriveConfigValidatorTest {

    @Test
    fun s3SupportsVirtualHostedAndPathStyleEndpoints() {
        assertTrue(AppConfig.XDRIVE_SERVICE_S3 in XdriveConfigValidator.services)
        assertTrue(XdriveConfigValidator.usesSecrets(AppConfig.XDRIVE_SERVICE_S3))
        assertFalse(XdriveConfigValidator.usesSecrets(AppConfig.XDRIVE_SERVICE_LOCAL))
        assertTrue(XdriveConfigValidator.isValidS3(s3Json()))
        assertTrue(XdriveConfigValidator.isValidS3(s3Json(region = "")))
        assertTrue(XdriveConfigValidator.isValidS3(s3Json(endpoint = "http://127.0.0.1:9000", pathStyle = true)))
        assertTrue(XdriveConfigValidator.isValidS3(s3Json(endpoint = "https://[::1]:9000/base", pathStyle = true)))
        assertTrue(XdriveConfigValidator.isValidS3("""{"endpoint":"https://s3.example.com","bucket":"bucket"}"""))
    }

    @Test
    fun s3RejectsInvalidObjectsAndMemberTypes() {
        listOf(
            null, "", "{", "[]", "{}",
            """{"endpoint":123,"bucket":"bucket"}""",
            """{"endpoint":"https://s3.example.com","bucket":false}""",
            """{"endpoint":"https://s3.example.com","bucket":"bucket","region":[]}""",
            """{"endpoint":"https://s3.example.com","bucket":"bucket","pathStyle":"true"}"""
        ).forEach { assertFalse(XdriveConfigValidator.isValidS3(it), it) }
    }

    @Test
    fun s3RejectsEndpointCredentialsQueriesFragmentsAndVirtualHostedIpAddresses() {
        listOf(
            "", "s3.example.com", "ftp://s3.example.com", "https:///bucket",
            "https://user:pass@s3.example.com", "https://s3.example.com?token=secret",
            "https://s3.example.com?", "https://s3.example.com#fragment",
            "https://127.0.0.1:9000", "https://[::1]:9000"
        ).forEach { assertFalse(XdriveConfigValidator.isValidS3(s3Json(endpoint = it)), it) }
    }

    @Test
    fun s3RejectsInvalidRegionsAndBuckets() {
        listOf("us/east-1", "us east-1", "us,east-1", "us\teast-1", "us\neast-1").forEach {
            assertFalse(XdriveConfigValidator.isValidS3(s3Json(region = it)), it)
        }
        listOf("", ".", "..", "a/b", "a\\b", "a\nb").forEach {
            assertFalse(XdriveConfigValidator.isValidS3(s3Json(bucket = it, pathStyle = true)), it)
        }
        listOf("Bucket", "a_b", "a..b", ".bucket", "bucket.").forEach {
            assertFalse(XdriveConfigValidator.isValidS3(s3Json(bucket = it)), it)
        }
    }

    @Test
    fun s3RequiresARelativePrefixAndAllowsOneTrailingSlash() {
        listOf("streams", "streams/client", "streams/client/").forEach {
            assertTrue(XdriveConfigValidator.isValidS3RemoteFolder(it), it)
        }
        listOf(null, "", "/", "/streams", "streams//", "a//b", "a/./b", "a/../b", "a\\b", "a\nb").forEach {
            assertFalse(XdriveConfigValidator.isValidS3RemoteFolder(it), it)
        }
    }

    @Test
    fun s3AcceptsTwoCredentialsAndAnOptionalSessionTokenWithoutShiftingBlankSlots() {
        listOf("access, secret", "access,secret,token", "access, secret, ").forEach {
            assertTrue(XdriveConfigValidator.parseSecrets(it)?.let(XdriveConfigValidator::isValidS3Secrets) == true)
        }
        listOf(
            null, "", "access", "access, secret, token, extra", "access, , token", ", secret, token",
            "access/key, secret", "access key, secret", "\"access,key\", secret", "access, sec\u0000ret"
        ).forEach {
            assertFalse(XdriveConfigValidator.parseSecrets(it)?.let(XdriveConfigValidator::isValidS3Secrets) == true)
        }
        assertEquals(listOf("access", "", "token"), XdriveConfigValidator.parseSecrets("access, , token"))
    }

    @Test
    fun parseSecretsSupportsQuotedCommasAndRejectsLegacyLines() {
        assertEquals(
            listOf("access", "secret,with,commas", "token\"suffix"),
            XdriveConfigValidator.parseSecrets("access, \"secret,with,commas\", \"token\"\"suffix\"")
        )
        assertEquals(null, XdriveConfigValidator.parseSecrets("access\nsecret\ntoken"))
        assertEquals(null, XdriveConfigValidator.parseSecrets("access, \"unfinished"))
    }

    private fun s3Json(
        endpoint: String = "https://s3.example.com",
        bucket: String = "bucket",
        region: String = "us-east-1",
        pathStyle: Boolean = false
    ): String = JsonUtil.toJson(
        mapOf("endpoint" to endpoint, "bucket" to bucket, "region" to region, "pathStyle" to pathStyle)
    )
}
