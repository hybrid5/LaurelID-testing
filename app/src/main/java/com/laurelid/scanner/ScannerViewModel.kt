package com.laurelid.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laurelid.auth.MdocParseException
import com.laurelid.auth.ParsedMdoc
import com.laurelid.config.AdminConfig
import com.laurelid.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val credentialParser: CredentialParser,
    private val verificationExecutor: VerificationExecutor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state

    private var verificationJob: Job? = null
    private var configSnapshot: AdminConfig = AdminConfig()

    fun updateConfig(config: AdminConfig) {
        configSnapshot = config
        _state.update { it.copy(demoMode = config.demoMode) }
    }

    fun submitQrPayload(payload: String, demoPayload: Boolean = false) {
        startVerification(demoPayload) { credentialParser.fromQr(payload) }
    }

    fun submitNfcPayload(payload: ByteArray) {
        startVerification(demoPayload = false) { credentialParser.fromNfc(payload) }
    }

    fun submitDemoPayload(payloadProvider: () -> String) {
        if (!_state.value.demoMode) return
        submitQrPayload(payloadProvider.invoke(), demoPayload = true)
    }

    fun onResultConsumed() {
        val demoMode = _state.value.demoMode
        _state.update { ScannerUiState(demoMode = demoMode) }
    }

    fun onErrorConsumed() {
        _state.update { it.copy(errorMessageRes = null) }
    }

    private fun startVerification(demoPayload: Boolean, parser: suspend () -> ParsedMdoc) {
        if (_state.value.isProcessing) return
        verificationJob?.cancel()
        verificationJob = viewModelScope.launch {
            _state.update { it.copy(phase = ScannerUiState.Phase.Verifying, isProcessing = true, errorMessageRes = null) }
            val parsed = runCatching { withContext(dispatcher) { parser() } }
            val verificationResult = parsed.fold(
                onSuccess = { parsedMdoc ->
                    runCatching {
                        verificationExecutor.verify(parsedMdoc, configSnapshot, demoPayload)
                    }.getOrElse { throwable ->
                        verificationExecutor.buildClientFailureResult(parsedMdoc)
                            .also { handleError(throwable) }
                    }
                },
                onFailure = { throwable ->
                    handleError(throwable)
                    null
                }
            )
            if (verificationResult != null) {
                _state.update {
                    it.copy(
                        phase = ScannerUiState.Phase.Result,
                        isProcessing = false,
                        result = verificationResult,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        phase = ScannerUiState.Phase.Scanning,
                        isProcessing = false,
                        errorMessageRes = ERROR_MESSAGE,
                    )
                }
            }
            verificationJob = null
        }
    }

    private fun handleError(error: Throwable) {
        if (error is MdocParseException) {
            Logger.w(TAG, "Credential parsing failed: ${error.error.detail}", error)
        } else {
            Logger.e(TAG, "Unexpected verification error", error)
        }
    }

    companion object {
        // Resource IDs aren’t compile-time constants in Kotlin, so this must be a regular val.
        private val ERROR_MESSAGE: Int = com.laurelid.R.string.result_details_error_unknown
        private const val TAG = "ScannerViewModel"
    }
}
