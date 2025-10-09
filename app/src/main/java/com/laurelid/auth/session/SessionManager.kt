package com.laurelid.auth.session

import com.augustcellars.cbor.CBORObject
import com.laurelid.auth.cose.CoseVerifier
import com.laurelid.auth.cose.VerifiedIssuer
import com.laurelid.auth.crypto.HpkeEngine
import com.laurelid.auth.crypto.HpkeEngine.Companion.DEFAULT_KEY_ALIAS
import com.laurelid.auth.crypto.HpkeKeyProvider
import com.laurelid.auth.deviceengagement.TransportType
import com.laurelid.auth.trust.TrustStore
import com.laurelid.auth.verifier.PresentationRequestBuilder
import com.laurelid.util.Logger
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates QR/NFC/BLE engagements and cryptographic verification. */
@Singleton
class SessionManager @Inject constructor(
    private val hpkeEngine: HpkeEngine,
    private val keyProvider: HpkeKeyProvider,
    private val coseVerifier: CoseVerifier,
    private val trustStore: TrustStore,
    private val presentationBuilder: PresentationRequestBuilder,
    private val webTransport: WebEngagementTransport,
    private val nfcTransport: NfcEngagementTransport,
    private val bleTransport: BleEngagementTransport,
    private val clock: Clock,
) {

    private val random = SecureRandom()
    private val mutex = Mutex()
    private var activeSession: ActiveSession? = null

    suspend fun createSession(preferred: TransportType = TransportType.WEB): ActiveSession = mutex.withLock {
        keyProvider.ensureKey(DEFAULT_KEY_ALIAS)
        hpkeEngine.initRecipient(DEFAULT_KEY_ALIAS)
        val nonce = ByteArray(32).also { random.nextBytes(it) }
        val request = VerificationRequest(
            docType = PresentationRequestBuilder.DEFAULT_DOC_TYPE,
            elements = presentationBuilder.requestedAttributes(),
            nonce = nonce,
            verifierPublicKey = keyProvider.getPublicKeyBytes(),
        )
        val transport = selectTransport(preferred, request)
        val engagement = transport.transport.start(request)
        val session = ActiveSession(request, transport, engagement)
        activeSession = session
        session
    }

    suspend fun decryptAndVerify(session: ActiveSession, ciphertext: ByteArray): VerificationResult = mutex.withLock {
        check(activeSession == session) { "Session is no longer active" }
        val plaintext = hpkeEngine.decrypt(ciphertext, session.engagement.transcript)
        val response = parseDeviceResponse(plaintext)
        val roots = trustStore.loadIacaRoots()
        val issuer: VerifiedIssuer = coseVerifier.verifyIssuer(response.issuerSigned, roots)
        if (!VerifierFeatureFlags.devProfileMode && response.deviceCertificates.isNotEmpty()) {
            trustStore.verifyChain(response.deviceCertificates, listOf(issuer.signerCert), clock.instant())
        }
        val deviceSignatureValid = coseVerifier.verifyDeviceSignature(
            response.deviceSignature,
            buildTranscript(session),
            response.deviceCertificates,
        )
        val minimized = presentationBuilder.minimize(issuer.claims)
        val result = VerificationResult(
            isSuccess = deviceSignatureValid,
            minimalClaims = minimized,
            portrait = issuer.claims[PORTRAIT_KEY] as? ByteArray,
            audit = buildAuditEntries(issuer, deviceSignatureValid, minimized.keys),
        )
        if (deviceSignatureValid) {
            cleanup()
        }
        Logger.i(TAG, "Verification completed success=$deviceSignatureValid")
        result
    }

    fun buildTranscript(session: ActiveSession): ByteArray = session.engagement.transcript

    fun encodeRequestQr(session: ActiveSession): ByteArray? =
        if (session.transport.type == TransportType.WEB) session.engagement.transcript else null

    fun encodeNfcHandover(session: ActiveSession): ByteArray? = session.engagement.peerInfo

    suspend fun cancel() = mutex.withLock {
        cleanup()
    }

    private suspend fun cleanup() {
        activeSession?.transport?.transport?.stop()
        activeSession = null
    }

    private fun selectTransport(preferred: TransportType, request: VerificationRequest): SessionTransport {
        val transport = when (preferred) {
            TransportType.WEB -> SessionTransport(TransportType.WEB, webTransport)
            TransportType.NFC -> SessionTransport(TransportType.NFC, nfcTransport)
            TransportType.BLE -> SessionTransport(TransportType.BLE, bleTransport)
        }
        if (transport.type == TransportType.WEB) {
            webTransport.stage(request)
        }
        return transport
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

    private fun buildAuditEntries(issuer: VerifiedIssuer, signatureValid: Boolean, elements: Set<String>): List<String> =
        buildList {
            add("issuer=${issuer.signerCert.subjectX500Principal.name}")
            add("signatureValid=$signatureValid")
            add("elements=${elements.joinToString()}")
        }

    data class ActiveSession(
        val request: VerificationRequest,
        val transport: SessionTransport,
        val engagement: EngagementSession,
    )

    data class SessionTransport(
        val type: TransportType,
        val transport: EngagementTransport,
    )

    data class VerificationRequest(
        val docType: String,
        val elements: List<String>,
        val nonce: ByteArray,
        val verifierPublicKey: ByteArray,
    )

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

    companion object {
        private const val PORTRAIT_KEY = "portrait"
        private const val TAG = "SessionManager"
    }
}

