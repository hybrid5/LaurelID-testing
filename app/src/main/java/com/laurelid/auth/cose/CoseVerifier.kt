// app/src/main/java/com/laurelid/auth/cose/CoseVerifier.kt
package com.laurelid.auth.cose

import COSE.Message
import COSE.OneKey
import COSE.Sign1Message
import com.laurelid.auth.session.VerificationError
import com.laurelid.auth.session.VerifierFeatureFlags
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
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

interface CoseVerifier {
    fun verifyIssuer(payload: ByteArray, trustAnchors: List<X509Certificate>, at: Instant): VerifiedIssuer
    fun verifyDeviceSignature(deviceSignature: ByteArray, transcript: ByteArray, deviceChain: List<X509Certificate>): Boolean
    fun extractAttributes(issuer: VerifiedIssuer, requested: Collection<String>): Map<String, Any?>
}

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
        val decoded = try {
            Message.DecodeFromBytes(payload)
        } catch (t: Throwable) {
            throw VerificationError.IssuerUntrusted("MSO is not COSE", t)
        }
        val message = decoded as? Sign1Message
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

        val ok = sign1Validate(message, OneKey(signer.publicKey, null))
        if (!ok) throw VerificationError.IssuerUntrusted("Issuer signature validation failed")

        val payloadCbor: ByteArray = getContentBytes(message)
            ?: throw VerificationError.IssuerUntrusted("MSO payload missing")

        val claims = decodeClaims(payloadCbor)
        return VerifiedIssuer(signer, claims)
    }

    override fun verifyDeviceSignature(
        deviceSignature: ByteArray,
        transcript: ByteArray,
        deviceChain: List<X509Certificate>,
    ): Boolean {
        if (deviceChain.isEmpty()) return false
        val msg = try {
            val decoded = Message.DecodeFromBytes(deviceSignature)
            decoded as? Sign1Message ?: return false
        } catch (_: Throwable) {
            return false
        }
        val aad = MessageDigest.getInstance("SHA-256").digest(transcript)
        setExternal(msg, aad)
        val publicKey = deviceChain.first().publicKey
        return sign1Validate(msg, OneKey(publicKey, null))
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
        // COSE header label for x5chain is 33 (numeric)
        val x5cLabel = CBORObject.FromObject(33)
        val protectedAttrs = message.protectedAttributes
        val unprotectedAttrs = message.unprotectedAttributes
        val x5chain: CBORObject = (protectedAttrs?.get(x5cLabel) as? CBORObject)
            ?: (unprotectedAttrs?.get(x5cLabel) as? CBORObject)
            ?: throw VerificationError.IssuerUntrusted("Missing X5Chain")

        if (x5chain.type != CBORType.Array) {
            throw VerificationError.IssuerUntrusted("x5c header is not an array")
        }

        val cf = CertificateFactory.getInstance("X.509")
        val count = x5chain.size()
        return buildList(count) {
            for (i in 0 until count) {
                val der = x5chain[i].GetByteString()
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
            .getOrElse { t -> throw VerificationError.IssuerUntrusted("Issuer chain validation failed", t) }
    }

    private fun decodeClaims(payload: ByteArray): Map<String, Any?> {
        val cbor = CBORObject.DecodeFromBytes(payload)
        val claims = mutableMapOf<String, Any?>()
        for (key in cbor.keys) {
            val claimKey = key.AsString()
            val v = cbor[key]
            val value: Any? = when {
                v == null -> null
                v.type == CBORType.Boolean -> v.AsBoolean()
                v.isNumber -> v.ToObject(Number::class.java)
                v.type == CBORType.TextString -> v.AsString()
                v.type == CBORType.ByteString -> v.GetByteString()
                else -> v.ToJSONString()
            }
            claims[claimKey] = value
        }
        return claims
    }

    // -------- COSE method shims (handles different library method names) --------

    private fun sign1Validate(msg: Sign1Message, key: OneKey): Boolean {
        return try {
            // Prefer Java-style 'Validate'
            val m = msg.javaClass.getMethod("Validate", OneKey::class.java)
            m.invoke(msg, key) as Boolean
        } catch (_: NoSuchMethodException) {
            val m = msg.javaClass.getMethod("validate", OneKey::class.java)
            m.invoke(msg, key) as Boolean
        }
    }

    private fun setExternal(msg: Sign1Message, aad: ByteArray) {
        try {
            val m = msg.javaClass.getMethod("SetExternal", ByteArray::class.java)
            m.invoke(msg, aad)
        } catch (_: NoSuchMethodException) {
            val m = msg.javaClass.getMethod("setExternal", ByteArray::class.java)
            m.invoke(msg, aad)
        }
    }

    private fun getContentBytes(msg: Sign1Message): ByteArray? {
        return try {
            val m = msg.javaClass.getMethod("GetContent")
            m.invoke(msg) as? ByteArray
        } catch (_: NoSuchMethodException) {
            val m = msg.javaClass.getMethod("getContent")
            m.invoke(msg) as? ByteArray
        }
    }
}
