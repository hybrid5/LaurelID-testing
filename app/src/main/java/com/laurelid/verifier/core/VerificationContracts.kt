package com.laurelid.verifier.core

import com.laurelid.verifier.transport.EngagementSession

/**
 * Immutable description of the attributes the kiosk is requesting from the mobile credential.
 *
 * The default document type matches ISO 18013-5/-7 mobile driving licenses. Elements map to
 * device-retrievable namespaces such as `age_over_21` or `family_name`.
 */
data class VerificationRequest(
    val docType: String = "org.iso.18013.5.1.mDL",
    val elements: List<String>,
    val nonce: ByteArray,
    val verifierPublicKey: ByteArray,
)

/**
 * Result of a verification transaction. The kiosk only retains the minimal claims it requested
 * plus an optional portrait. Audit entries are human readable breadcrumbs for operator review.
 */
data class VerificationResult(
    val isSuccess: Boolean,
    val minimalClaims: Map<String, Any?>,
    val portrait: ByteArray?,
    val audit: List<String>,
)

/**
 * Tracks high level session state across the engagement → session → verification lifecycle.
 */
sealed interface VerifierState {
    data object Idle : VerifierState
    data class AwaitingResponse(val request: VerificationRequest, val engagement: EngagementSession) : VerifierState
    data class Completed(val result: VerificationResult) : VerifierState
    data class Failed(val reason: String, val engagement: EngagementSession?) : VerifierState
}
