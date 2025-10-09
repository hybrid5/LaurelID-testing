// app/src/main/java/com/laurelid/ui/kiosk/KioskViewModel.kt
package com.laurelid.ui.kiosk

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laurelid.auth.deviceengagement.TransportType
import com.laurelid.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Use the auth SessionManager explicitly to avoid ambiguity with mdoc version
import com.laurelid.auth.session.SessionManager as AuthSessionManager
import com.laurelid.auth.session.WebEngagementTransport as AuthWebEngagementTransport

@HiltViewModel
class KioskViewModel @Inject constructor(
    private val sessionManager: AuthSessionManager,
    private val webTransport: AuthWebEngagementTransport,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Keep track of the active session so we can decrypt/cleanup
    private var activeSession: AuthSessionManager.ActiveSession? = null

    init {
        // Reflect QR changes from the web transport into UI state
        viewModelScope.launch {
            webTransport.qrCodes()
                .filterNotNull()
                .collect { qr ->
                    _state.update {
                        it.copy(
                            qr = QrUi(sessionId = qr.sessionId, bitmap = qr.bitmap),
                            status = "Show QR to wallet",
                            loading = false,
                            resultText = null
                        )
                    }
                }
        }
    }

    fun startSession(preferred: TransportType = TransportType.WEB) {
        viewModelScope.launch {
            _state.update {
                it.copy(status = "Starting session…", loading = true, resultText = null, qr = null)
            }
            runCatching { sessionManager.createSession(preferred) }
                .onSuccess { session ->
                    activeSession = session
                    val waiting = when (preferred) {
                        TransportType.WEB -> "Waiting for wallet…"
                        TransportType.NFC -> "Waiting for NFC tap…"
                        TransportType.BLE -> "Waiting for BLE wallet…"
                    }
                    _state.update { it.copy(status = waiting, loading = false) }
                }
                .onFailure { t ->
                    Logger.e(TAG, "Failed to start session", t)
                    _state.update {
                        it.copy(status = "Failed to start session", loading = false, resultText = t.message)
                    }
                }
        }
    }

    fun reset() {
        viewModelScope.launch {
            try {
                sessionManager.cancel()
            } catch (_: Throwable) {
                // ignore
            } finally {
                activeSession = null
                _state.value = UiState()
            }
        }
    }

    /**
     * Provide the HPKE ciphertext received from the wallet. On success, updates result banner.
     */
    fun onCiphertextReceived(ciphertext: ByteArray) {
        val session = activeSession ?: return
        viewModelScope.launch {
            _state.update { it.copy(status = "Decrypting…", loading = true) }
            runCatching {
                sessionManager.decryptAndVerify(session, ciphertext)
            }.onSuccess { result ->
                val verdict = if (result.isSuccess) "Verified" else "Denied"
                _state.update {
                    it.copy(
                        status = "Result: $verdict",
                        loading = false,
                        resultText = buildString {
                            append(verdict)
                            if (result.minimalClaims.isNotEmpty()) {
                                append(" • ")
                                append(result.minimalClaims.entries.joinToString { e -> "${e.key}=${e.value}" })
                            }
                        }
                    )
                }
                if (result.isSuccess) {
                    activeSession = null
                }
            }.onFailure { t ->
                Logger.e(TAG, "Verification failed", t)
                _state.update { it.copy(status = "Verification failed", loading = false, resultText = t.message) }
            }
        }
    }

    data class UiState(
        val status: String = "Idle",
        val loading: Boolean = false,
        val qr: QrUi? = null,
        val resultText: String? = null,
    )

    data class QrUi(
        val sessionId: String?,
        val bitmap: Bitmap?
    )

    companion object {
        private const val TAG = "KioskViewModel"
    }
}
