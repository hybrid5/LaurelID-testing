package com.laurelid.auth.cose

import com.augustcellars.cose.HeaderKeys
import com.augustcellars.cose.OneKey
import com.augustcellars.cose.Sign1Message
import com.laurelid.auth.session.VerificationError
import com.laurelid.auth.session.VerifierFeatureFlags
import com.upokecenter.cbor.CBORObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies issuer and device signatures contained in ISO/IEC 18013-5 Mobile Security Objects.
 */
interface CoseVerifier {
    fun verifyIssuer(payload: ByteArray, trustAnchors: List<X509Certificate>, at: Instant): VerifiedIssuer
    fun verifyDeviceSignature(deviceSignature: ByteArray, transcript: ByteArray, deviceChain: List<X509Certificate>): Boolean
    fun extractAttributes(issuer: VerifiedIssuer, requested: Collection<String>): Map<String, Any?>
}

/** Result of verifying the COSE_Sign1 issuer message. */
data class VerifiedIssuer(
    val signerCert: X509Certificate,
    val claims: Map<String, Any?>,
)

@Singleton
class DefaultCoseVerifier @Inject constructor() : CoseVerifier {

    override fun verifyIssuer(
        payload: ByteArray,
        trustAnchors: List<X509Certificate>,
        at: Instant,
    ): VerifiedIssuer {
        val message = try {
            Sign1Message.DecodeFromBytes(payload)
        } catch (throwable: Throwable) {
            throw VerificationError.IssuerUntrusted("MSO is not COSE_Sign1", throwable)
        }

        val certificates = extractCertificates(message)
        if (certificates.isEmpty()) {
            throw VerificationError.IssuerUntrusted("MSO missing signer certificate")
        }

        if (!VerifierFeatureFlags.devProfileMode) {
            validateChain(certificates, trustAnchors, at)
        }

        val signer = certificates.first()
        try {
            signer.checkValidity(Date.from(at))
        } catch (expired: CertificateExpiredException) {
            throw VerificationError.IssuerCertificateExpired("Issuer certificate expired", expired)
        } catch (notYetValid: CertificateNotYetValidException) {
            throw VerificationError.IssuerUntrusted("Issuer certificate not yet valid", notYetValid)
        }

        if (!message.Validate(OneKey(signer.publicKey, null))) {
            throw VerificationError.IssuerUntrusted("Issuer signature validation failed")
        }

        val payloadCbor = message.GetContent()
            ?: throw VerificationError.IssuerUntrusted("MSO payload missing")
        val claims = decodeClaims(payloadCbor) // ISO/IEC 18013-5 §9
        return VerifiedIssuer(signer, claims)
    }

    override fun verifyDeviceSignature(
        deviceSignature: ByteArray,
        transcript: ByteArray,
        deviceChain: List<X509Certificate>,
    ): Boolean {
        if (deviceChain.isEmpty()) return false
        val message = try {
            Sign1Message.DecodeFromBytes(deviceSignature)
        } catch (_: Throwable) {
            return false
        }
        val aad = MessageDigest.getInstance("SHA-256").digest(transcript)
        message.SetExternal(aad)
        val publicKey = deviceChain.first().publicKey
        return message.Validate(OneKey(publicKey, null))
    }

    override fun extractAttributes(issuer: VerifiedIssuer, requested: Collection<String>): Map<String, Any?> {
        if (requested.isEmpty()) return emptyMap()
        val claims = issuer.claims
        return buildMap {
            for (key in requested) {
                if (claims.containsKey(key)) put(key, claims[key])
            }
        }
    }

    private fun extractCertificates(message: Sign1Message): List<X509Certificate> {
        val x5chain = message.protectedAttributes[HeaderKeys.X5Chain]
            ?: message.unprotectedAttributes[HeaderKeys.X5Chain]
            ?: throw VerificationError.IssuerUntrusted("Missing X5Chain")

        require(x5chain.isArray) { "x5c chain missing" }

        val cf = CertificateFactory.getInstance("X.509")
        return buildList {
            for (index in 0 until x5chain.size()) {
                val der = x5chain[index].GetByteString()
                add(cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate)
            }
        }
    }

    private fun validateChain(
        chain: List<X509Certificate>,
        anchors: List<X509Certificate>,
        at: Instant,
    ) {
        if (chain.isEmpty()) {
            throw VerificationError.IssuerUntrusted("Empty certificate chain")
        }
        val trustAnchors = anchors.map { TrustAnchor(it, null) }.toSet()
        if (trustAnchors.isEmpty()) {
            throw VerificationError.IssuerTrustUnavailable("No IACA trust anchors configured")
        }
        val cf = CertificateFactory.getInstance("X.509")
        val certPath = cf.generateCertPath(chain)
        val params = PKIXParameters(trustAnchors).apply {
            isRevocationEnabled = false
            date = Date.from(at)
        }
        val validator = CertPathValidator.getInstance("PKIX")
        runCatching { validator.validate(certPath, params) }
            .getOrElse { throwable ->
                throw VerificationError.IssuerUntrusted("Issuer chain validation failed", throwable)
            }
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
