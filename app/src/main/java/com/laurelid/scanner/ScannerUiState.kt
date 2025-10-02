package com.laurelid.scanner

import androidx.annotation.StringRes
import com.laurelid.R
import com.laurelid.data.VerificationResult

/**
 * Immutable snapshot of the scanner UI.
 */
data class ScannerUiState(
    val phase: Phase = Phase.Scanning,
    val isProcessing: Boolean = false,
    val demoMode: Boolean = false,
    val result: VerificationResult? = null,
    @StringRes val errorMessageRes: Int? = null,
) {
    enum class Phase { Scanning, Verifying, Result }

    val statusTextRes: Int
        @StringRes get() = when (phase) {
            Phase.Scanning -> R.string.scanner_status_scanning
            Phase.Verifying -> R.string.scanner_status_verifying
            Phase.Result -> R.string.scanner_status_ready
        }

    val hintTextRes: Int
        @StringRes get() = when (phase) {
            Phase.Scanning -> R.string.scanner_hint
            Phase.Verifying -> R.string.scanner_status_verifying
            Phase.Result -> R.string.scanner_status_ready
        }

    val showProgress: Boolean get() = phase == Phase.Verifying
}
