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

    init {
        viewModelScope.launch {
            webTransport.qrCodes().collectLatest { qrState ->
                updateState { it.copy(qrCode = qrState?.bitmap) }
            }
        }
        restartSession()
    }

    fun onEncryptedPayload(payload: ByteArray) {
        viewModelScope.launch {
            updateState { it.copy(stage = VerificationUiState.Stage.PENDING) }
            val result = coordinator.processResponse(payload)
            handleResult(result)
        }
    }

    fun restartSession() {
        viewModelScope.launch {
            sessionManager.cancel()
            runCatching { sessionManager.createSession(TransportType.WEB) }
                .onSuccess {
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
            coordinator.cancel()
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
        viewModelScope.launch { sessionManager.cancel() }
    }

    companion object {
        private const val AUTO_RESET_DELAY_MS = 15_000L
    }
}
