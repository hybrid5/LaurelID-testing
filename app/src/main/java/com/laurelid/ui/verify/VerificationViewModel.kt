package com.laurelid.ui.verify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laurelid.verifier.core.VerificationCoordinator
import com.laurelid.verifier.core.VerifierState
import com.laurelid.verifier.core.VerificationResult
import com.laurelid.verifier.transport.QrEngagementTransport
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
    private val coordinator: VerificationCoordinator,
    private val qrTransport: QrEngagementTransport,
) : ViewModel() {

    private val _state = MutableStateFlow(VerificationUiState())
    val state: StateFlow<VerificationUiState> = _state

    private var autoResetJob: Job? = null

    init {
        viewModelScope.launch {
            qrTransport.qrCodes().collectLatest { qrState ->
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
            val state = coordinator.begin(DEFAULT_ELEMENTS)
            when (state) {
                is VerifierState.AwaitingResponse -> updateState {
                    it.copy(stage = VerificationUiState.Stage.WAITING, errorMessage = null, result = null)
                }
                is VerifierState.Failed -> updateState {
                    it.copy(
                        stage = VerificationUiState.Stage.RESULT,
                        errorMessage = state.reason,
                        result = null,
                    )
                }
                else -> Unit
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
    }

    companion object {
        private val DEFAULT_ELEMENTS = listOf("age_over_21", "given_name", "family_name", "portrait")
        private const val AUTO_RESET_DELAY_MS = 15_000L
    }
}
