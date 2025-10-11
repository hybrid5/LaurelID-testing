package com.laurelid.auth.session

import android.app.Activity
import android.graphics.Bitmap
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.laurelid.BuildConfig
import com.laurelid.auth.ISO18013Parser
import com.laurelid.util.Logger
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.laurelid.auth.session.SessionManager.VerificationRequest

/** Common contract for engagement transports (ISO/IEC 18013-5 §7). */
interface EngagementTransport {
    suspend fun start(request: VerificationRequest): EngagementSession
    suspend fun stop()
}

data class EngagementSession(
    val sessionId: String,
    val transcript: ByteArray,
    val peerInfo: ByteArray? = null,
)

@Singleton
class WebEngagementTransport @Inject constructor() : EngagementTransport {

    private val stagedRequest = AtomicReference<VerificationRequest?>()
    private val qrState = MutableStateFlow<QrCodeState?>(null)

    fun qrCodes(): StateFlow<QrCodeState?> = qrState

    fun stage(request: VerificationRequest) {
        stagedRequest.set(request)
    }

    override suspend fun start(request: VerificationRequest): EngagementSession {
        stage(request)
        val sessionId = UUID.randomUUID().toString()
        val encoded = encodeRequest(sessionId, request)
        qrState.value = QrCodeState(sessionId, renderQrCode(encoded))
        return EngagementSession(sessionId, encoded)
    }

    override suspend fun stop() {
        qrState.value = null
        stagedRequest.set(null)
    }

    private fun encodeRequest(sessionId: String, request: VerificationRequest): ByteArray {
        val json = buildString {
            append('{')
            append("\"sessionId\":\"").append(sessionId).append('\"')
            append(',')
            append("\"docType\":\"").append(request.docType).append('\"')
            append(',')
            append("\"elements\":[")
            append(request.elements.joinToString(separator = ",") { "\"$it\"" })
            append(']')
            append(',')
            append("\"nonce\":\"${request.nonce.toBase64()}\"")
            append(',')
            append("\"verifierKey\":\"${request.verifierPublicKey.toBase64()}\"")
            append('}')
        }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun ByteArray.toBase64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun renderQrCode(payload: ByteArray): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(String(payload, StandardCharsets.UTF_8), BarcodeFormat.QR_CODE, 800, 800)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) QR_FOREGROUND else QR_BACKGROUND)
            }
        }
        return bitmap
    }

    data class QrCodeState(val sessionId: String, val bitmap: Bitmap)

    private companion object {
        private const val QR_FOREGROUND = 0xFF000000.toInt()
        private const val QR_BACKGROUND = 0xFFFFFFFF.toInt()
    }
}

@Singleton
class NfcEngagementTransport @Inject constructor(
    private val adapterProvider: NfcAdapterProvider,
) : EngagementTransport, NfcAdapter.ReaderCallback {

    private val activityRef = AtomicReference<WeakReference<Activity>?>(null)
    private val sessionRef = AtomicReference<EngagementSession?>(null)
    private val sessionFlow = MutableStateFlow<EngagementSession?>(null)

    fun bind(activity: Activity) {
        activityRef.set(WeakReference(activity))
    }

    fun sessions(): StateFlow<EngagementSession?> = sessionFlow

    override suspend fun start(request: VerificationRequest): EngagementSession {
        val adapter = adapterProvider.get() ?: error("NFC adapter unavailable")
        val activity = activityRef.get()?.get() ?: error("Activity binding required before start")
        val session = EngagementSession(UUID.randomUUID().toString(), ByteArray(0))
        sessionRef.set(session)
        sessionFlow.value = session
        adapter.enableReaderMode(
            activity,
            this,
            NFC_FLAGS,
            Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 2000) },
        )
        return session
    }

    override suspend fun stop() {
        val adapter = adapterProvider.get() ?: return
        val activity = activityRef.get()?.get() ?: return
        adapter.disableReaderMode(activity)
        sessionRef.set(null)
        sessionFlow.value = null
    }

    override fun onTagDiscovered(tag: Tag) {
        val engagement = sessionRef.get() ?: return
        var ndef: Ndef? = null
        try {
            ndef = Ndef.get(tag) ?: return
            ndef.connect()
            val payload = ndef.ndefMessage?.records?.firstOrNull()?.payload ?: ByteArray(0)
            if (payload.isEmpty() || payload.contentEquals(engagement.peerInfo)) return
            val updated = engagement.copy(
                transcript = engagement.transcript + payload,
                peerInfo = payload,
            )
            sessionRef.set(updated)
            sessionFlow.value = updated
            if (BuildConfig.DEBUG) {
                Logger.d(TAG, "NFC engagement payloadHash=${ISO18013Parser.hashPreview(payload)}")
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to read NFC tag", t)
        } finally {
            try { ndef?.close() } catch (_: Throwable) { /* ignore */ }
        }
    }

    companion object {
        private const val TAG = "NfcEngagement"
        private const val NFC_FLAGS =
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}

fun interface NfcAdapterProvider {
    fun get(): NfcAdapter?
}
