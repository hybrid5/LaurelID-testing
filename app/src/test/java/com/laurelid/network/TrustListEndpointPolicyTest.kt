package com.laurelid.network

import com.laurelid.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TrustListEndpointPolicyTest {
    @Test
    fun `default base URL is https`() {
        assertTrue(TrustListEndpointPolicy.defaultBaseUrl.startsWith("https://"))
    }

    @Test
    fun `normalize override trims and normalizes`() {
        val normalized = TrustListEndpointPolicy.normalizeOverrideOrNull(
            " https://example.com/path ",
            allow = true,
        )
        assertEquals("https://example.com/path", normalized)
    }

    @Test
    fun `normalize override rejects http`() {
        val normalized = TrustListEndpointPolicy.normalizeOverrideOrNull(
            "http://example.com",
            allow = true,
        )
        assertNull(normalized)
    }

    @Test
    fun `certificate pins present for default host`() {
        val pins = TrustListEndpointPolicy.certificatePinsFor(TrustListEndpointPolicy.defaultBaseUrl)
        assertTrue(pins.isNotEmpty())
        pins.forEach { (host, pin) ->
            assertTrue(host.isNotBlank())
            assertTrue(pin.startsWith("sha256/"))
        }
    }

    @Test
    fun `certificate pin rotation honours expiry grace`() {
        val baseUrl = TrustListEndpointPolicy.defaultBaseUrl
        val secondaryPin = "sha256/Sq9GtEtHd5FcNhLBA7ZnWD9E/RE0KhwvhJ8apGLI1qI"
        val beforeExpiry = TrustListEndpointPolicy.certificatePinsFor(
            baseUrl,
            nowMillis = 1748736000000L - 1L,
        )
        assertTrue(beforeExpiry.size >= 2)

        val afterFirstGrace = TrustListEndpointPolicy.certificatePinsFor(
            baseUrl,
            nowMillis = 1748736000000L + BuildConfig.TRUST_LIST_PIN_EXPIRY_GRACE_MILLIS + 1L,
        )
        assertTrue(afterFirstGrace.size >= 1)
        assertTrue(afterFirstGrace.all { it.second == secondaryPin })

        val afterAllExpired = TrustListEndpointPolicy.certificatePinsFor(
            baseUrl,
            nowMillis = 1780272000000L + BuildConfig.TRUST_LIST_PIN_EXPIRY_GRACE_MILLIS + 1L,
        )
        assertTrue(afterAllExpired.isEmpty())
    }

    @Test
    fun `require endpoint allowed honours build flag`() {
        val alternate = "https://override.example.com/"
        if (TrustListEndpointPolicy.allowOverride) {
            assertNotNull(TrustListEndpointPolicy.requireEndpointAllowed(alternate))
        } else {
            try {
                TrustListEndpointPolicy.requireEndpointAllowed(alternate)
                fail("Expected IllegalArgumentException when overrides disabled")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
