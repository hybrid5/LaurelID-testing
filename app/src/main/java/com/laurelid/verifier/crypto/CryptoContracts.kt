package com.laurelid.verifier.crypto

import java.security.cert.X509Certificate

/**
 * HPKE decryptor that unwraps the encrypted payload returned by the mobile device.
 */
interface HpkeEngine {
    fun decrypt(enc: ByteArray, aad: ByteArray? = null): ByteArray
}

/**
 * COSE/MSO verifier that validates the issuer signature and the device binding signature.
 */
interface CoseVerifier {
    fun verifyIssuer(msoCose: ByteArray, trustAnchors: List<X509Certificate>): VerifiedIssuer
    fun verifyDeviceSignature(
        deviceSig: ByteArray,
        transcript: ByteArray,
        deviceChain: List<X509Certificate>,
    ): Boolean
}

/** Holds the verified issuer certificate and parsed claims. */
data class VerifiedIssuer(
    val signerCert: X509Certificate,
    val claims: Map<String, Any?>,
)
