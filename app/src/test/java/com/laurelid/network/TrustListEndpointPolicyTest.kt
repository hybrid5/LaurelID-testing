package com.laurelid.network

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
