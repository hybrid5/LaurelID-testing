package com.laurelid.crypto

import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import com.laurelid.mdoc.DeviceResponseFormat
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.util.encoders.Hex

/**
 * Verifies Mobile Security Object signatures produced during wallet presentation (ISO/IEC 18013-5 §9). 【ISO18013-5§9】
 */
interface CoseVerifier {
    suspend fun verifyIssuer(payload: ByteArray, trustAnchors: List<X509Certificate>): VerifiedIssuer
    suspend fun verifyDeviceSignature(
        deviceSignature: ByteArray,
        transcript: ByteArray,
        deviceChain: List<X509Certificate>,
    ): Boolean
    fun extractAttributes(issuer: VerifiedIssuer, requested: Collection<String>): Map<String, Any?>
}

/** Result of verifying the COSE_Sign1 issuer message. */
data class VerifiedIssuer(
    val signerCert: X509Certificate,
    val claims: Map<String, Any?>,
    val format: DeviceResponseFormat = DeviceResponseFormat.COSE_SIGN1,
)

@Singleton
class DefaultCoseVerifier @Inject constructor() : CoseVerifier {

    override suspend fun verifyIssuer(payload: ByteArray, trustAnchors: List<X509Certificate>): VerifiedIssuer =
        withContext(Dispatchers.Default) {
            val message = Sign1Message.DecodeFromBytes(payload)
            val certificates = extractCertificates(message)
            if (certificates.isEmpty()) error("MSO missing signer certificate")
            validateChain(certificates, trustAnchors, requireAnchors = true)
            val signer = certificates.first()
            if (!message.validate(OneKey(signer.publicKey, null))) {
                error("Issuer signature validation failed")
            }
            val claims = decodeClaims(message.payload ?: error("MSO missing payload"))
            VerifiedIssuer(signer, claims)
        }

    override suspend fun verifyDeviceSignature(
        deviceSignature: ByteArray,
        transcript: ByteArray,
        deviceChain: List<X509Certificate>,
    ): Boolean = withContext(Dispatchers.Default) {
        if (deviceChain.isEmpty()) return@withContext false
        val message = Sign1Message.DecodeFromBytes(deviceSignature)
        val aad = MessageDigest.getInstance("SHA-256").digest(transcript)
        message.setExternal(aad)
        val publicKey = deviceChain.first().publicKey
        validateChain(deviceChain, emptyList(), requireAnchors = false)
        message.validate(OneKey(publicKey, null))
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
        val chain = header[HeaderKeys.X5CHAIN.AsCBOR()] ?: return emptyList()
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
        requireAnchors: Boolean,
    ) {
        if (chain.isEmpty()) error("Empty certificate chain")
        if (anchors.isEmpty()) {
            if (requireAnchors) error("No trust anchors configured") else return
        }
        val trustAnchors = anchors.map { TrustAnchor(it, null) }.toSet()
        val cf = CertificateFactory.getInstance("X.509")
        val certPath = cf.generateCertPath(chain)
        val params = PKIXParameters(trustAnchors).apply { isRevocationEnabled = false }
        val validator = CertPathValidator.getInstance("PKIX")
        validator.validate(certPath, params)
    }

    private fun decodeClaims(payload: ByteArray): Map<String, Any?> {
        val cbor = com.upokecenter.cbor.CBORObject.DecodeFromBytes(payload)
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
                else -> Hex.toHexString(value.EncodeToBytes())
            }
        }
        return claims
    }
}
