package com.laurelid.auth.session

/** Verification failure reasons surfaced to the kiosk orchestration layer. */
sealed class VerificationError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class DeviceCertUntrusted(message: String) : VerificationError(message)
    class TrustAnchorsUnavailable(message: String, cause: Throwable? = null) : VerificationError(message, cause)
    class IssuerTrustUnavailable(message: String, cause: Throwable? = null) : VerificationError(message, cause)
    class IssuerUntrusted(message: String, cause: Throwable? = null) : VerificationError(message, cause)
    class IssuerCertificateExpired(message: String, cause: Throwable? = null) : VerificationError(message, cause)
}
