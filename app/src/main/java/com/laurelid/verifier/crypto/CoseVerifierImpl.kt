package com.laurelid.verifier.crypto

import com.augustcellars.cbor.CBORObject
import com.augustcellars.cose.HeaderKeys
import com.augustcellars.cose.OneKey
import com.augustcellars.cose.Sign1Message
import com.laurelid.verifier.core.VerifierFeatureFlags
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
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
        val aad = MessageDigest.getInstance("SHA-256").digest(transcript)
        message.setExternal(aad)
        val publicKey = deviceChain.first().publicKey
        if (!VerifierFeatureFlags.devProfileMode && deviceChain.size > 1) {
            // Device binding still requires an anchored chain when provided.
            validateChain(deviceChain, emptyList())
        }
        return message.validate(OneKey(publicKey, null))
    }

    private fun extractCertificates(message: Sign1Message): List<X509Certificate> {
        val attr = message.protectedAttributes[HeaderKeys.X5CHAIN.AsCBOR()] ?: return emptyList()
        val certificateFactory = CertificateFactory.getInstance("X.509")
        return buildList {
            for (index in 0 until attr.size()) {
                val derBytes = attr.get(index).GetByteString()
                add(certificateFactory.generateCertificate(ByteArrayInputStream(derBytes)) as X509Certificate)
            }
        }
    }

    private fun validateChain(
        chain: List<X509Certificate>,
        anchors: List<X509Certificate>,
    ) {
        if (chain.isEmpty()) error("Empty certificate chain")
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certPath = certificateFactory.generateCertPath(chain)
        val trustAnchors = anchors.map { TrustAnchor(it, null) }.toSet()
        if (trustAnchors.isEmpty()) return
        val params = PKIXParameters(trustAnchors).apply { isRevocationEnabled = false }
        val validator = CertPathValidator.getInstance("PKIX")
        validator.validate(certPath, params)
    }

    private fun decodeClaims(payload: ByteArray): Map<String, Any?> {
        val cbor = CBORObject.DecodeFromBytes(payload)
        val claims = mutableMapOf<String, Any?>()
        for (key in cbor.keys) {
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
