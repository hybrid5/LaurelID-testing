package com.laurelid.ui.kiosk

import android.graphics.Bitmap
import com.laurelid.auth.deviceengagement.TransportType
import com.laurelid.auth.session.EngagementSession
import com.laurelid.auth.session.SessionManager
import com.laurelid.auth.session.WebEngagementTransport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KioskFlowSmokeTest {

    @Test
    fun transitionsThroughStates() = runTest {
        val qrState = MutableStateFlow<WebEngagementTransport.QrCodeState?>(null)
        val webTransport = mockk<WebEngagementTransport> {
            every { qrCodes() } returns qrState
            every { stage(any()) } answers { qrState.update { WebEngagementTransport.QrCodeState("session", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) } }
        }
        val session = mockk<SessionManager.ActiveSession>(relaxed = true) {
            every { transport.type } returns TransportType.WEB
            every { engagement } returns EngagementSession("session", ByteArray(0))
        }
        val verificationResult = mockk<SessionManager.VerificationResult> {
            every { isSuccess } returns true
            every { minimalClaims } returns mapOf("age_over_21" to true)
            every { portrait } returns null
            every { audit } returns emptyList()
        }
        val sessionManager = mockk<SessionManager> {
            coEvery { createSession(any()) } returns session
            coEvery { decryptAndVerify(session, any()) } returns verificationResult
            coEvery { cancel() } returns Unit
        }

        val viewModel = KioskViewModel(sessionManager, webTransport)
        viewModel.startSession()
        assertTrue(viewModel.state.value is KioskState.WaitingApproval)
        viewModel.onCiphertextReceived(ByteArray(0))
        assertTrue(viewModel.state.value is KioskState.Verified)
    }
}

