package com.laurelid.ui.kiosk

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.laurelid.deviceengagement.TransportType
import com.laurelid.mdoc.ActiveSession
import com.laurelid.mdoc.SessionManager
import com.laurelid.mdoc.VerificationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** ViewModel driving the kiosk Verify with Wallet UX. */
@HiltViewModel
class KioskViewModel @Inject constructor(
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow<KioskState>(KioskState.Idle)
    val state: StateFlow<KioskState> = _state

    private var activeSession: ActiveSession? = null

    fun startSession(preferred: TransportType = TransportType.QR) {
        viewModelScope.launch {
            _state.value = KioskState.Engaging
            runCatching { sessionManager.startSession(preferred) }
                .onSuccess { session ->
                    activeSession = session
                    val qrBitmap = sessionManager.handshakePayload(session)?.let(::renderQr)
                    _state.value = KioskState.WaitingApproval(session.id, qrBitmap)
                }
                .onFailure { error ->
                    _state.value = KioskState.Failed(error.message ?: "Unable to start session")
                }
        }
    }

    fun onCiphertextReceived(payload: ByteArray) {
        val session = activeSession ?: return
        viewModelScope.launch {
            _state.value = KioskState.Decrypting
            val result = runCatching { sessionManager.processCiphertext(session, payload) }
            result.onSuccess(::handleResult).onFailure { error ->
                _state.value = KioskState.Failed(error.message ?: "Decryption failed")
            }
        }
    }

    fun reset() {
        viewModelScope.launch {
            sessionManager.cancel()
            activeSession = null
            _state.value = KioskState.Idle
        }
    }

    private fun handleResult(result: VerificationResult) {
        _state.value = if (result.isSuccess) {
            KioskState.Verified(result)
        } else {
            KioskState.Denied(result, reason = "Device attestation invalid")
        }
    }

    private fun renderQr(payload: ByteArray): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(String(payload, Charsets.UTF_8), BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) QR_FOREGROUND else QR_BACKGROUND)
            }
        }
        return bitmap
    }

    companion object {
        private const val QR_SIZE = 800
        private const val QR_FOREGROUND = 0xFF000000.toInt()
        private const val QR_BACKGROUND = 0xFFFFFFFF.toInt()
    }
}
