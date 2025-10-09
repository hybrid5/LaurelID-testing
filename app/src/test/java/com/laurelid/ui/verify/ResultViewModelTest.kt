package com.laurelid.ui.verify

import com.laurelid.auth.session.VerificationError
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultViewModelTest {

    private val viewModel = ResultViewModel()

    @Test
    fun mapsIssuerTrustUnavailable() {
        val code = viewModel.mapException(VerificationError.IssuerTrustUnavailable("No anchors"))
        assertEquals(ResultViewModel.ErrorCode.NO_TRUST_ANCHORS, code)
    }

    @Test
    fun mapsExpiredSigner() {
        val code = viewModel.mapException(VerificationError.IssuerCertificateExpired("Expired"))
        assertEquals(ResultViewModel.ErrorCode.SIGNER_EXPIRED, code)
    }

    @Test
    fun mapsChainMismatch() {
        val code = viewModel.mapException(VerificationError.IssuerUntrusted("Chain mismatch"))
        assertEquals(ResultViewModel.ErrorCode.CHAIN_MISMATCH, code)
    }

    @Test
    fun mapsDeviceSignatureInvalid() {
        val code = viewModel.mapDeviceSignature(valid = false)
        assertEquals(ResultViewModel.ErrorCode.DEVICE_SIG_INVALID, code)
    }

    @Test
    fun defaultsToUnknown() {
        val code = viewModel.mapException(IllegalStateException("boom"))
        assertEquals(ResultViewModel.ErrorCode.UNKNOWN, code)
    }
}
