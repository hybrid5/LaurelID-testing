package com.laurelid.ui.kiosk

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laurelid.auth.deviceengagement.TransportType
import com.laurelid.auth.session.SessionManager
import com.laurelid.auth.session.VerifierFeatureFlags
import com.laurelid.auth.session.WebEngagementTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class KioskViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val webTransport: WebEngagementTransport,
) : ViewModel() {

    private val _state = MutableStateFlow<KioskState>(KioskState.Idle)
    val state: StateFlow<KioskState> = _state

    private var activeSession: SessionManager.ActiveSession? = null

    init {
        viewModelScope.launch {
            webTransport.qrCodes().collectLatest { qr ->
                val current = _state.value
                if (current is KioskState.WaitingApproval && qr != null) {
                    _state.value = current.copy(qrBitmap = qr.bitmap, sessionId = qr.sessionId)
                }
            }
        }
    }

    fun startSession(preferred: TransportType = defaultTransport()) {
        viewModelScope.launch {
            _state.value = KioskState.Engaging
            runCatching { sessionManager.createSession(preferred) }
                .onFailure { error ->
                    _state.value = KioskState.Failed(error.message ?: "Unable to start session")
                }
                .onSuccess { session ->
                    activeSession = session
                    val qr = webTransport.qrCodes().value
                    _state.value = KioskState.WaitingApproval(
                        session = session,
                        qrBitmap = qr?.bitmap,
                        sessionId = qr?.sessionId,
                    )
                }
        }
    }

    fun onCiphertextReceived(payload: ByteArray) {
        val session = activeSession ?: return
        viewModelScope.launch {
            _state.value = KioskState.Decrypting
            val result = runCatching { sessionManager.decryptAndVerify(session, payload) }
            result.onSuccess { verification ->
                if (verification.isSuccess) {
                    _state.value = KioskState.Verified(verification)
                } else {
                    _state.value = KioskState.Failed("Device signature invalid")
                }
            }.onFailure { error ->
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

    private fun defaultTransport(): TransportType =
        when {
            VerifierFeatureFlags.qrEnabled -> TransportType.WEB
            VerifierFeatureFlags.nfcEnabled -> TransportType.NFC
            else -> TransportType.BLE
        }
}

sealed interface KioskState {
    data object Idle : KioskState
    data object Engaging : KioskState
    data class WaitingApproval(
        val session: SessionManager.ActiveSession,
        val qrBitmap: Bitmap?,
        val sessionId: String?,
    ) : KioskState
    data object Decrypting : KioskState
    data class Verified(val result: SessionManager.VerificationResult) : KioskState
    data class Failed(val reason: String) : KioskState
}

