package com.laurelid.mdoc

import com.augustcellars.cbor.CBORObject
import com.laurelid.crypto.CoseVerifier
import com.laurelid.crypto.TrustStore
import com.laurelid.crypto.VerifiedIssuer
import com.laurelid.deviceengagement.TransportMessage
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs ISO/IEC 18013-5 Mobile Security Object validation after HPKE decryption. 【ISO18013-5§9】
 */
@Singleton
class Verifier @Inject constructor(
    private val coseVerifier: CoseVerifier,
    private val trustStore: TrustStore,
    private val presentationBuilder: PresentationRequestBuilder,
    private val clock: Clock,
) {

    suspend fun verify(message: TransportMessage): VerificationResult {
        val plaintext = message.payload
        val response = parseDeviceResponse(plaintext)
        val roots = trustStore.loadIacaRoots()
        val issuer: VerifiedIssuer = coseVerifier.verifyIssuer(response.issuerSigned, roots)
        if (response.deviceCertificates.isNotEmpty()) {
            trustStore.verifyChain(response.deviceCertificates, listOf(issuer.signerCert), clock.instant())
        }
        val transcript = message.transcript ?: ByteArray(0)
        val deviceSignatureValid = coseVerifier.verifyDeviceSignature(
            response.deviceSignature,
            transcript,
            response.deviceCertificates,
        )
        val minimized = presentationBuilder.minimize(issuer.claims)
        return VerificationResult(
            isSuccess = deviceSignatureValid,
            minimalClaims = minimized,
            portrait = issuer.claims[PresentationRequestBuilder.PORTRAIT] as? ByteArray,
            audit = buildAuditEntries(issuer, deviceSignatureValid, minimized.keys, message.engagementNonce),
        )
    }

    private fun parseDeviceResponse(plaintext: ByteArray): DeviceResponse {
        val cbor = CBORObject.DecodeFromBytes(plaintext)
        val issuerSigned = cbor["issuerSigned"].GetByteString()
        val deviceSignature = cbor["deviceSignature"].GetByteString()
        val certArray = cbor["deviceCertificates"]
        val certificates = mutableListOf<X509Certificate>()
        val factory = CertificateFactory.getInstance("X.509")
        for (i in 0 until certArray.size()) {
            val der = certArray[i].GetByteString()
            certificates += factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
        return DeviceResponse(issuerSigned, deviceSignature, certificates)
    }

    private fun buildAuditEntries(
        issuer: VerifiedIssuer,
        signatureValid: Boolean,
        elements: Set<String>,
        nonce: ByteArray?,
    ): List<String> = buildList {
        add("issuer=${issuer.signerCert.subjectX500Principal.name}")
        add("signatureValid=$signatureValid")
        add("elements=${elements.joinToString()}")
        nonce?.let { add("nonce=${it.joinToString(separator = "") { b -> String.format("%02x", b) }}") }
    }
}

/** Outcome of verification used to drive kiosk UX. */
data class VerificationResult(
    val isSuccess: Boolean,
    val minimalClaims: Map<String, Any?>,
    val portrait: ByteArray?,
    val audit: List<String>,
)

private data class DeviceResponse(
    val issuerSigned: ByteArray,
    val deviceSignature: ByteArray,
    val deviceCertificates: List<X509Certificate>,
)
