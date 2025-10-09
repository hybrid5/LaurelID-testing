package com.laurelid.auth.cose

import COSE.HeaderKeys
import COSE.Message
import COSE.OneKey
import COSE.Sign1Message
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
        val decoded = Message.DecodeFromBytes(payload)
        val message = (decoded as? Sign1Message)
            ?: throw VerificationError.IssuerUntrusted("MSO is not COSE_Sign1")

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

        if (!message.validate(OneKey(signer.publicKey, null))) {
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
        val decoded = Message.DecodeFromBytes(deviceSignature)
        val message = (decoded as? Sign1Message) ?: return false
        val aad = MessageDigest.getInstance("SHA-256").digest(transcript)
        message.setExternal(aad)
        val publicKey = deviceChain.first().publicKey
        return message.validate(OneKey(publicKey, null))
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
        val header = message.protectedAttributes
        // Try known label, then raw label (33) as fallback to handle older/newer COSE libs.
        val x5ChainKey = try { HeaderKeys.X5CHAIN.AsCBOR() } catch (_: Throwable) { null }
        val chain = when {
            x5ChainKey != null && header.ContainsKey(x5ChainKey) -> header[x5ChainKey]
            header.ContainsKey(CBORObject.FromObject(33)) -> header[CBORObject.FromObject(33)]
            else -> return emptyList()
        }
        val cf = CertificateFactory.getInstance("X.509")
        val certificates = ArrayList<X509Certificate>()
        for (index in 0 until chain.size()) {
            val der = chain[index].GetByteString()
            certificates += cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
        return certificates
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
