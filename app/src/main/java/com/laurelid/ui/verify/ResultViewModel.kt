package com.laurelid.ui.verify

import androidx.lifecycle.ViewModel
import com.laurelid.auth.session.VerificationError
class ResultViewModel : ViewModel() {

    enum class ErrorCode {
        NO_TRUST_ANCHORS,
        SIGNER_EXPIRED,
        CHAIN_MISMATCH,
        DEVICE_SIG_INVALID,
        UNKNOWN,
    }

    fun mapException(throwable: Throwable?): ErrorCode = when (throwable) {
        is VerificationError.IssuerTrustUnavailable -> ErrorCode.NO_TRUST_ANCHORS
        is VerificationError.IssuerCertificateExpired -> ErrorCode.SIGNER_EXPIRED
        is VerificationError.DeviceCertUntrusted -> ErrorCode.CHAIN_MISMATCH
        is VerificationError.IssuerUntrusted -> ErrorCode.CHAIN_MISMATCH
        else -> ErrorCode.UNKNOWN
    }

    fun mapDeviceSignature(valid: Boolean): ErrorCode? =
        if (valid) null else ErrorCode.DEVICE_SIG_INVALID
}
