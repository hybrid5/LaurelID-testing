package com.laurelid.mdoc

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds presentation requests according to ISO/IEC 18013-5 §8 with minimal attribute disclosure. 【ISO18013-5§8】
 */
@Singleton
class PresentationRequestBuilder @Inject constructor() {

    private val random = SecureRandom()

    fun createRequest(publicKey: ByteArray, docType: String = DEFAULT_DOC_TYPE): PresentationRequest {
        val nonce = ByteArray(32).also(random::nextBytes)
        return PresentationRequest(
            docType = docType,
            requestedElements = requestedAttributes(),
            nonce = nonce,
            verifierPublicKey = publicKey,
        )
    }

    fun requestedAttributes(): List<String> = listOf(
        AGE_OVER_21,
        GIVEN_NAME,
        FAMILY_NAME,
        PORTRAIT,
    )

    fun minimize(claims: Map<String, Any?>): Map<String, Any?> {
        val requested = requestedAttributes().toSet()
        return claims.filterKeys { it in requested }
    }

    companion object {
        const val DEFAULT_DOC_TYPE = "org.iso.18013.5.1.mDL"
        const val AGE_OVER_21 = "age_over_21"
        const val GIVEN_NAME = "given_name"
        const val FAMILY_NAME = "family_name"
        const val PORTRAIT = "portrait"
    }
}

/** Presentation request payload surfaced in the QR engagement. */
data class PresentationRequest(
    val docType: String,
    val requestedElements: List<String>,
    val nonce: ByteArray,
    val verifierPublicKey: ByteArray,
)
