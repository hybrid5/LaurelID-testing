package com.laurelid.auth

// Assuming ParsedMdoc is defined in this package (com.laurelid.auth) or imported correctly,
// and has at least fields: issuer, ageOver21, subjectDid, docType.
// The build log for ScannerActivity implies com.laurelid.auth.ParsedMdoc.
import com.laurelid.data.VerificationResult
import com.laurelid.network.TrustListRepository
import com.laurelid.util.Logger

class WalletVerifier(private val trustListRepository: TrustListRepository) {

    suspend fun verify(parsed: ParsedMdoc, maxCacheAgeMillis: Long): VerificationResult {
        val trustList = try {
            // Attempt to get or refresh the trust list using maxCacheAgeMillis
            trustListRepository.getOrRefresh(maxCacheAgeMillis)
        } catch (throwable: Throwable) {
            // If refresh fails, log and fall back to a cached version, or an empty map if none.
            Logger.w(TAG, "Trust list fetch/refresh failed, falling back to cached version.", throwable)
            trustListRepository.cached() ?: emptyMap()
        }

        // Basic verification logic (to be expanded with COSE/CBOR as per your requirements)
        val issuerTrusted = !trustList.isNullOrEmpty() && trustList.containsKey(parsed.issuer)
        
        // Placeholder: Real signature, validity, and policy checks will go here.
        // For now, mirroring the previous logic structure.
        val agePolicySatisfied = parsed.ageOver21 
        val success = issuerTrusted && agePolicySatisfied // Simplified success condition

        var errorCode: String? = null // This will be replaced by the VerificationError sealed class

        if (!issuerTrusted) {
            Logger.w(TAG, "Issuer ${parsed.issuer} not trusted by current list.")
            errorCode = ERROR_UNTRUSTED_ISSUER // Placeholder for specific error
        } else if (!agePolicySatisfied) {
            // Assuming ageOver21 is the primary policy check for now
            Logger.w(TAG, "Age policy (ageOver21) not satisfied for ${parsed.subjectDid}.")
            errorCode = ERROR_POLICY_NOT_MET // Placeholder for specific error
        }
        // Add other checks (signature, expiry, revocation) here and update errorCode accordingly

        // TODO: Implement full COSE signature validation, revocation checks, and docType/validity window enforcement.
        // TODO: Integrate the VerificationError sealed class for the 'error' field.
        // TODO: Ensure ParsedMdoc and VerificationResult classes have all necessary fields based on full mdoc parsing.
        // TODO: Implement trustStale flag logic based on TrustListRepository state.
        return VerificationResult(
            success = success,
            ageOver21 = parsed.ageOver21, // Explicitly returning ageOver21 as in previous structure
            issuer = parsed.issuer,
            subjectDid = parsed.subjectDid, // Added
            docType = parsed.docType,       // Added
            error = errorCode
            // trustStale = ... // This will be added based on TrustListRepository's state
        )
    }

    companion object {
        private const val TAG = "WalletVerifier"
        // These constants should be replaced by instances of the VerificationError sealed class later.
        private const val ERROR_UNTRUSTED_ISSUER = "UNTRUSTED_ISSUER"
        private const val ERROR_POLICY_NOT_MET = "POLICY_NOT_MET"
        // Kept for now if any old logic relies on it, but ideally remove.
        private const val ERROR_UNTRUSTED_OR_UNDERAGE = "UNTRUSTED_OR_UNDERAGE" 
    }
}
