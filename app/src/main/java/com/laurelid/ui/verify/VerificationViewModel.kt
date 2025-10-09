package com.laurelid.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laurelid.auth.deviceengagement.TransportType
import com.laurelid.auth.session.SessionManager
import com.laurelid.auth.session.SessionManager.VerificationResult
import com.laurelid.auth.session.WebEngagementTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val webTransport: WebEngagementTransport,
) : ViewModel() {

    private val _state = MutableStateFlow(VerificationUiState())
    val state: StateFlow<VerificationUiState> = _state

    private var autoResetJob: Job? = null
    private var activeSession: SessionManager.ActiveSession? = null

    init {
        viewModelScope.launch {
            webTransport.qrCodes().collectLatest { qrState ->
                updateState { it.copy(qrCode = qrState?.bitmap) }
            }
        }
        restartSession()
    }

    fun onEncryptedPayload(payload: ByteArray) {
        val session = activeSession
        if (session == null) {
            updateState {
                it.copy(
                    stage = VerificationUiState.Stage.RESULT,
                    result = null,
                    errorMessage = "No active session",
                )
            }
            return
        }
        viewModelScope.launch {
            updateState { it.copy(stage = VerificationUiState.Stage.PENDING) }
            val result = runCatching { sessionManager.decryptAndVerify(session, payload) }
                .getOrElse { error ->
                    VerificationResult(
                        isSuccess = false,
                        minimalClaims = emptyMap(),
                        portrait = null,
                        audit = listOfNotNull(error.message),
                    )
                }
            handleResult(result)
        }
    }

    fun restartSession() {
        viewModelScope.launch {
            autoResetJob?.cancel()
            sessionManager.cancel()
            activeSession = null
            runCatching { sessionManager.createSession(TransportType.WEB) }
                .onSuccess {
                    activeSession = it
                    updateState {
                        it.copy(stage = VerificationUiState.Stage.WAITING, errorMessage = null, result = null)
                    }
                }
                .onFailure { error ->
                    updateState {
                        it.copy(
                            stage = VerificationUiState.Stage.RESULT,
                            errorMessage = error.message,
                            result = null,
                        )
                    }
                }
        }
    }

    private fun handleResult(result: VerificationResult) {
        activeSession = null
        updateState {
            it.copy(
                stage = VerificationUiState.Stage.RESULT,
                result = result,
                errorMessage = result.takeUnless { res -> res.isSuccess }?.audit?.lastOrNull(),
            )
        }
        autoResetJob?.cancel()
        autoResetJob = viewModelScope.launch {
            delay(AUTO_RESET_DELAY_MS)
            sessionManager.cancel()
            activeSession = null
            updateState { VerificationUiState() }
            restartSession()
        }
    }

    private inline fun updateState(block: (VerificationUiState) -> VerificationUiState) {
        _state.value = block(_state.value)
    }

    override fun onCleared() {
        super.onCleared()
        autoResetJob?.cancel()
        viewModelScope.launch {
            sessionManager.cancel()
            activeSession = null
        }
    }

    companion object {
        private const val AUTO_RESET_DELAY_MS = 15_000L
    }
}
