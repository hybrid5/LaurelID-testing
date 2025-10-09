package com.laurelid.verifier.transport

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.laurelid.verifier.core.VerificationRequest
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Displays a QR code containing the ephemeral session bootstrap payload for Wallet engagement.
 */
@Singleton
class QrEngagementTransport @Inject constructor() : EngagementTransport {

    private val stagedRequest = AtomicReference<VerificationRequest?>()
    private val qrState = MutableStateFlow<QrCodeState?>(null)

    fun qrCodes(): StateFlow<QrCodeState?> = qrState

    fun stage(request: VerificationRequest) {
        stagedRequest.set(request)
    }

    override suspend fun start(): EngagementSession {
        val request = stagedRequest.get() ?: error("QR transport requires a staged request")
        val sessionId = UUID.randomUUID().toString()
        val encoded = encodeRequest(sessionId, request)
        qrState.value = QrCodeState(sessionId, renderQrCode(encoded))
        return EngagementSession(sessionId, encoded)
    }

    override suspend fun stop() {
        qrState.value = null
    }

    private fun encodeRequest(sessionId: String, request: VerificationRequest): ByteArray {
        val json = JSONObject().apply {
            put("sessionId", sessionId)
            put("docType", request.docType)
            put("elements", request.elements)
            put("nonce", Base64.encodeToString(request.nonce, Base64.NO_WRAP))
            put("verifierKey", Base64.encodeToString(request.verifierPublicKey, Base64.NO_WRAP))
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun renderQrCode(payload: ByteArray): Bitmap {
        val qrWriter = QRCodeWriter()
        val data = String(payload, StandardCharsets.UTF_8)
        val matrix = qrWriter.encode(data, BarcodeFormat.QR_CODE, 800, 800)
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
        const val QR_FOREGROUND = 0xFF000000.toInt()
        const val QR_BACKGROUND = 0xFFFFFFFF.toInt()
    }
}
