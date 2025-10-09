package com.laurelid.verifier.crypto

import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import com.upokecenter.cbor.CBORObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.inject.Inject

/**
 * Verifies COSE_Sign1 issuer messages and device signatures.
 *
 * Depends on:
 *  - interface CoseVerifier
 *  - data class VerifiedIssuer(val certificate: X509Certificate, val claims: Map<String, Any?>)
 *  - object VerifierFeatureFlags { val devProfileMode: Boolean }
 *
 * Libraries:
 *  - 'com.augustcellars.cose:cose-java' for COSE
 *  - 'com.upokecenter:cbor' for CBOR parsing
 */
class CoseVerifierImpl @Inject constructor() : CoseVerifier {

    override fun verifyIssuer(
        msoCose: ByteArray,
        trustAnchors: List<X509Certificate>,
    ): VerifiedIssuer {
        val message = Sign1Message.DecodeFromBytes(msoCose)
        val certificates = extractCertificates(message)
        if (certificates.isEmpty()) {
            error("MSO does not include signer certificate chain")
        }

        if (!VerifierFeatureFlags.devProfileMode) {
            validateChain(certificates, trustAnchors)
        }

        val signerCert = certificates.first()
        if (!message.validate(OneKey(signerCert.publicKey, null))) {
            error("Issuer signature validation failed")
        }

        val payload = message.payload ?: error("MSO missing payload")
        val claims = decodeClaims(payload)

        return VerifiedIssuer(signerCert, claims)
    }

    override fun verifyDeviceSignature(
        deviceSig: ByteArray,
        transcript: ByteArray,
        deviceChain: List<X509Certificate>,
    ): Boolean {
        if (deviceChain.isEmpty()) return false

        val message = Sign1Message.DecodeFromBytes(deviceSig)

        // Bind the signature to the session transcript (AAD)
        val aad = MessageDigest.getInstance("SHA-256").digest(transcript)
        message.setExternal(aad)

        val publicKey = deviceChain.first().publicKey

        if (!VerifierFeatureFlags.devProfileMode && deviceChain.size > 1) {
            // When a chain is presented by the device, require it to validate.
            validateChain(deviceChain, emptyList())
        }

        return message.validate(OneKey(publicKey, null))
    }

    /**
     * Extracts an X.509 certificate chain from the COSE protected header (x5chain).
     */
    private fun extractCertificates(message: Sign1Message): List<X509Certificate> {
        val prot = message.protectedAttributes
        val x5chain = prot[HeaderKeys.X5CHAIN.AsCBOR()] ?: return emptyList()

        val cf = CertificateFactory.getInstance("X.509")
        val out = ArrayList<X509Certificate>()

        // x5chain is a CBOR array of DER-encoded cert bytes
        val count = x5chain.size()
        for (i in 0 until count) {
            val der = x5chain[i].GetByteString()
            out.add(cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate)
        }
        return out
    }

    /**
     * Validates a certificate path against the given trust anchors (if any).
     */
    private fun validateChain(
        chain: List<X509Certificate>,
        anchors: List<X509Certificate>,
    ) {
        if (chain.isEmpty()) error("Empty certificate chain")

        val trustAnchors = anchors.map { TrustAnchor(it, null) }.toSet()
        if (trustAnchors.isEmpty()) return  // nothing to validate against

        val cf = CertificateFactory.getInstance("X.509")
        val certPath = cf.generateCertPath(chain)

        val params = PKIXParameters(trustAnchors).apply {
            isRevocationEnabled = false
        }

        val validator = CertPathValidator.getInstance("PKIX")
        validator.validate(certPath, params)
    }

    /**
     * Decodes a CBOR payload into a simple Kotlin map.
     */
    private fun decodeClaims(payload: ByteArray): Map<String, Any?> {
        val cbor = CBORObject.DecodeFromBytes(payload)
        val claims = mutableMapOf<String, Any?>()

        // Explicit cast to avoid ambiguous iterator() on some JDKs
        for (key in (cbor.keys as Iterable<CBORObject>)) {
            val claimKey = key.AsString()
            val value = cbor[key]
            claims[claimKey] = when {
                value == null -> null
                value.isBoolean -> value.AsBoolean()
                value.isNumber -> value.ToObject(Number::class.java)
                value.isTextString -> value.AsString()
                value.isByteString -> value.GetByteString()
                else -> value.ToString()
            }
        }
        return claims
    }
}
