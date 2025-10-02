package com.laurelid.ui

import com.laurelid.auth.ParsedMdoc
import com.laurelid.auth.VerifierService
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
}
