package com.laurelid.verifier.core

import com.augustcellars.cbor.CBORObject
import com.laurelid.util.Logger
import com.laurelid.verifier.crypto.CoseVerifier
import com.laurelid.verifier.crypto.HpkeEngine
import com.laurelid.verifier.crypto.HpkeKeyProvider
import com.laurelid.verifier.trust.TrustStore
import com.laurelid.verifier.transport.BleEngagementTransport
import com.laurelid.verifier.transport.EngagementSession
import com.laurelid.verifier.transport.EngagementTransport
import com.laurelid.verifier.transport.NfcEngagementTransport
import com.laurelid.verifier.transport.QrEngagementTransport
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class VerificationCoordinator @Inject constructor(
    private val hpkeEngine: HpkeEngine,
    private val coseVerifier: CoseVerifier,
    private val trustStore: TrustStore,
    private val keyProvider: HpkeKeyProvider,
    private val qrTransport: QrEngagementTransport,
    private val nfcTransport: NfcEngagementTransport,
    private val bleTransport: BleEngagementTransport,
) {

    private val secureRandom = SecureRandom()
    private val stateMutex = Mutex()
    private var activeTransport: EngagementTransport? = null
    private var activeSession: EngagementSession? = null
    private var activeRequest: VerificationRequest? = null
    private var currentState: VerifierState = VerifierState.Idle

    suspend fun begin(elements: List<String>): VerifierState = stateMutex.withLock {
        val nonce = ByteArray(32).also { secureRandom.nextBytes(it) }
        val request = VerificationRequest(
            elements = elements,
            nonce = nonce,
            verifierPublicKey = keyProvider.getPublicKeyBytes(),
        )
        activeRequest = request
        val transport = selectTransport(request)
        val session = runCatching { transport.start() }
            .onFailure { error -> Logger.e(TAG, "Failed to start transport", error) }
            .getOrElse {
                currentState = VerifierState.Failed("Unable to start engagement", null)
                return@withLock currentState
            }
        activeTransport = transport
        activeSession = session
        currentState = VerifierState.AwaitingResponse(request, session)
        currentState
    }

    suspend fun processResponse(encryptedPayload: ByteArray): VerificationResult = stateMutex.withLock {
        val session = activeSession ?: error("No active session")
        val request = activeRequest ?: error("No active request")
        val plaintext = hpkeEngine.decrypt(encryptedPayload)
        val response = parseDeviceResponse(plaintext)
        val issuer = coseVerifier.verifyIssuer(response.issuerSigned, trustStore.loadIacaRoots())
        val deviceSignatureValid = coseVerifier.verifyDeviceSignature(
            response.deviceSignature,
            session.transcript,
            response.deviceCertificates,
        )
        val minimalClaims = issuer.claims.filterKeys { it in request.elements }
        val result = VerificationResult(
            isSuccess = deviceSignatureValid,
            minimalClaims = minimalClaims,
            portrait = issuer.claims[CLAIM_PORTRAIT] as? ByteArray,
            audit = buildList {
                add("issuer=${issuer.signerCert.subjectX500Principal.name}")
                add("deviceSignatureValid=$deviceSignatureValid")
                add("elements=${minimalClaims.keys}")
            },
        )
        currentState = if (deviceSignatureValid) {
            VerifierState.Completed(result)
        } else {
            VerifierState.Failed("Device signature invalid", session)
        }
        cleanup()
        result
    }

    suspend fun cancel() = stateMutex.withLock {
        cleanup()
        currentState = VerifierState.Idle
    }

    fun state(): VerifierState = currentState

    private suspend fun cleanup() {
        activeTransport?.stop()
        activeTransport = null
        activeSession = null
        activeRequest = null
    }

    private fun selectTransport(request: VerificationRequest): EngagementTransport {
        if (VerifierFeatureFlags.qrEnabled) {
            qrTransport.stage(request)
            return qrTransport
        }
        if (VerifierFeatureFlags.nfcEnabled) {
            return nfcTransport
        }
        return bleTransport
    }

    private fun parseDeviceResponse(plaintext: ByteArray): DeviceResponse {
        val cbor = CBORObject.DecodeFromBytes(plaintext)
        val issuerSigned = cbor["issuerSigned"].GetByteString()
        val deviceSig = cbor["deviceSignature"].GetByteString()
        val certArray = cbor["deviceCertificates"]
        val certificates = mutableListOf<X509Certificate>()
        val certificateFactory = CertificateFactory.getInstance("X.509")
        for (index in 0 until certArray.size()) {
            val der = certArray.get(index).GetByteString()
            certificates += certificateFactory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
        return DeviceResponse(
            issuerSigned = issuerSigned,
            deviceSignature = deviceSig,
            deviceCertificates = certificates,
        )
    }

    private data class DeviceResponse(
        val issuerSigned: ByteArray,
        val deviceSignature: ByteArray,
        val deviceCertificates: List<X509Certificate>,
    )

    private companion object {
        private const val TAG = "VerificationCoordinator"
        private const val CLAIM_PORTRAIT = "portrait"
    }
}
