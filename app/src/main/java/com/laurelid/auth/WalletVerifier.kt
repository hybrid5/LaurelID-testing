package com.laurelid.auth

// Assuming ParsedMdoc is defined in this package (com.laurelid.auth) or imported correctly,
// and has at least fields: issuer, ageOver21, subjectDid, docType.
// The build log for ScannerActivity implies com.laurelid.auth.ParsedMdoc.
import com.laurelid.data.VerificationResult
import com.laurelid.network.TrustListRepository
import java.time.Clock

class WalletVerifier(
    trustListRepository: TrustListRepository,
    clock: Clock = Clock.systemUTC()
) {

    private val verifierService = VerifierService(trustListRepository, clock)

    suspend fun verify(parsed: ParsedMdoc, maxCacheAgeMillis: Long): VerificationResult {
        return verifierService.verify(parsed, maxCacheAgeMillis)
    }
}
