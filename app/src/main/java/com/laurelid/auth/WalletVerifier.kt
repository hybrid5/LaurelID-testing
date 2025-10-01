package com.laurelid.auth

// Assuming ParsedMdoc is defined in this package (com.laurelid.auth) or imported correctly,
// and has at least fields: issuer, ageOver21, subjectDid, docType.
// The build log for ScannerActivity implies com.laurelid.auth.ParsedMdoc.
import com.laurelid.data.VerificationResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletVerifier @Inject constructor(
    private val verifierService: VerifierService
) {

    suspend fun verify(parsed: ParsedMdoc, maxCacheAgeMillis: Long): VerificationResult {
        return verifierService.verify(parsed, maxCacheAgeMillis)
    }
}
