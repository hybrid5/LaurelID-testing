package com.laurelid.ui

import com.laurelid.auth.ParsedMdoc
import com.laurelid.auth.VerifierService
import com.laurelid.data.VerificationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ScannerActivityPrivacyTest {
    @Test
    fun `client failure result omits issuer and subject`() {
        val parsed = ParsedMdoc(
            subjectDid = "did:example:123",
            docType = "org.iso.18013.5.1.mDL",
            issuer = "ExampleIssuer",
            ageOver21 = true,
        )

        val result = ScannerActivity.buildClientFailureResult(parsed)

        assertFalse(result.success)
        assertEquals(VerifierService.ERROR_CLIENT_EXCEPTION, result.error)
        assertNull(result.issuer)
        assertNull(result.subjectDid)
        assertEquals(parsed.docType, result.docType)
    }

    @Test
    fun `sanitizeResult strips pii and normalizes reason code`() {
        val raw = VerificationResult(
            success = false,
            ageOver21 = false,
            issuer = "TopSecretIssuer",
            subjectDid = "did:example:leak",
            docType = "org.iso.18013.5.1.mDL",
            error = "invalid signature from did:example:leak",
            trustStale = null,
        )

        val sanitized = ScannerActivity.sanitizeResult(raw)

        assertFalse(sanitized.success)
        assertNull(sanitized.issuer)
        assertNull(sanitized.subjectDid)
        assertEquals(VerifierService.ERROR_CLIENT_EXCEPTION, sanitized.error)
        assertEquals(raw.docType, sanitized.docType)
    }
}
