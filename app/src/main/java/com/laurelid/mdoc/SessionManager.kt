package com.laurelid.mdoc

import android.util.Base64
import com.laurelid.crypto.HpkeEngine
import com.laurelid.crypto.HpkeEngine.Companion.DEFAULT_KEY_ALIAS
import com.laurelid.crypto.HpkeKeyProvider
import com.laurelid.deviceengagement.DeviceEngagement
import com.laurelid.deviceengagement.QrTransport
import com.laurelid.deviceengagement.Transport
import com.laurelid.deviceengagement.TransportDescriptor
import com.laurelid.deviceengagement.TransportFactory
import com.laurelid.deviceengagement.TransportMessage
import com.laurelid.deviceengagement.TransportType
import com.laurelid.mdoc.DeviceResponseFormat
import com.laurelid.mdoc.PresentationRequestBuilder.Companion.DEFAULT_DOC_TYPE
import com.laurelid.util.Logger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates engagement setup, HPKE decryption, and verification for kiosk sessions. 【ISO18013-7§7】【RFC9180§5】
 */
@Singleton
class SessionManager @Inject constructor(
    private val hpkeEngine: HpkeEngine,
    private val keyProvider: HpkeKeyProvider,
    private val presentationBuilder: PresentationRequestBuilder,
    private val transportFactory: TransportFactory,
    private val verifier: Verifier,
) {

    private val mutex = Mutex()
    private var activeSession: ActiveSession? = null

    suspend fun startSession(
        preferred: TransportType = TransportType.QR,
        docType: String = DEFAULT_DOC_TYPE,
    ): ActiveSession = mutex.withLock {
        keyProvider.ensureKey(DEFAULT_KEY_ALIAS)
        hpkeEngine.initRecipient(DEFAULT_KEY_ALIAS)
        val request = presentationBuilder.createRequest(keyProvider.getPublicKeyBytes(), docType)
        val sessionId = UUID.randomUUID().toString()
        val transcript = buildTranscript(sessionId, request)
        val descriptor = TransportDescriptor(
            type = TransportType.QR,
            supportedFormats = listOf(DeviceResponseFormat.COSE_SIGN1),
            engagementPayload = encodeQrPayload(sessionId, request, transcript),
            sessionTranscript = transcript,
            nonce = request.nonce,
        )
        val engagement = DeviceEngagement(version = 1, qr = descriptor)
        val transport = transportFactory.create(preferred, engagement)
        transport.start()
        val expectedFormat = (transport as? QrTransport)?.currentFormat() ?: DeviceResponseFormat.COSE_SIGN1
        return@withLock ActiveSession(
            id = sessionId,
            request = request,
            transport = transport,
            expectedFormat = expectedFormat,
            transcript = transcript,
            engagementPayload = descriptor.engagementPayload,
        ).also { activeSession = it }
    }

    suspend fun processCiphertext(session: ActiveSession, ciphertext: ByteArray): VerificationResult = mutex.withLock {
        check(activeSession?.id == session.id) { "Session is no longer active" }
        val result = hpkeEngine.decrypt(ciphertext, session.transcript).use { plaintext ->
            val payload = plaintext.copy()
            val message = TransportMessage(
                payload = payload,
                format = session.expectedFormat,
                transcript = session.transcript,
                engagementNonce = session.request.nonce,
            )
            verifier.verify(message)
        }
        if (result.isSuccess) {
            cleanup()
        }
        Logger.i(TAG, "Verification completed success=${result.isSuccess}")
        result
    }

    suspend fun cancel() = mutex.withLock { cleanup() }

    fun handshakePayload(session: ActiveSession): ByteArray? = session.engagementPayload

    private suspend fun cleanup() {
        activeSession?.transport?.stop()
        activeSession = null
    }

    private fun buildTranscript(sessionId: String, request: PresentationRequest): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sessionId.toByteArray(StandardCharsets.UTF_8))
        digest.update(request.docType.toByteArray(StandardCharsets.UTF_8))
        digest.update(request.nonce)
        for (element in request.requestedElements) {
            digest.update(element.toByteArray(StandardCharsets.UTF_8))
        }
        digest.update(request.verifierPublicKey)
        return digest.digest()
    }

    private fun encodeQrPayload(
        sessionId: String,
        request: PresentationRequest,
        transcript: ByteArray,
    ): ByteArray {
        val json = buildString {
            append('{')
            append("\"sessionId\":\"").append(sessionId).append('\"')
            append(',')
            append("\"docType\":\"").append(request.docType).append('\"')
            append(',')
            append("\"elements\":[")
            append(request.requestedElements.joinToString(separator = ",") { "\"$it\"" })
            append(']')
            append(',')
            append("\"nonce\":\"${request.nonce.toBase64()}\"")
            append(',')
            append("\"verifierKey\":\"${request.verifierPublicKey.toBase64()}\"")
            append(',')
            append("\"transcript\":\"${transcript.toBase64()}\"")
            append('}')
        }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    companion object {
        private const val TAG = "SessionManager"
    }
}

/** Active session metadata tracked by the kiosk state machine. */
data class ActiveSession(
    val id: String,
    val request: PresentationRequest,
    val transport: Transport,
    val expectedFormat: DeviceResponseFormat,
    val transcript: ByteArray,
    val engagementPayload: ByteArray?,
)
