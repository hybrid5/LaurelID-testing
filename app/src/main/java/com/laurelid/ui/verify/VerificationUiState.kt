package com.laurelid.ui.verify

import android.graphics.Bitmap
import com.laurelid.auth.session.SessionManager.VerificationResult

data class VerificationUiState(
    val stage: Stage = Stage.WAITING,
    val result: VerificationResult? = null,
    val qrCode: Bitmap? = null,
    val errorMessage: String? = null,
) {
    enum class Stage { WAITING, PENDING, RESULT }
}
