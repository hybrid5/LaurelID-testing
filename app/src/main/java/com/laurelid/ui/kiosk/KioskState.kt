package com.laurelid.ui.kiosk

import android.graphics.Bitmap
import com.laurelid.mdoc.VerificationResult

/** State machine for kiosk verification flow. Idle → Engaging → WaitingApproval → Verified/Denied → Reset. */
sealed interface KioskState {
    data object Idle : KioskState
    data object Engaging : KioskState
    data class WaitingApproval(
        val sessionId: String,
        val qrBitmap: Bitmap?,
    ) : KioskState
    data object Decrypting : KioskState
    data class Verified(val result: VerificationResult) : KioskState
    data class Denied(val result: VerificationResult, val reason: String) : KioskState
    data class Failed(val reason: String) : KioskState
}
