package com.laurelid.ui

import com.laurelid.deviceengagement.Transport
import com.laurelid.deviceengagement.TransportType
import com.laurelid.mdoc.ActiveSession
import com.laurelid.mdoc.PresentationRequest
import com.laurelid.mdoc.PresentationRequestBuilder
import com.laurelid.mdoc.VerificationResult
import com.laurelid.mdoc.SessionManager
import com.laurelid.ui.kiosk.KioskState
import com.laurelid.ui.kiosk.KioskViewModel
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class KioskFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start session transitions to waiting approval`() = runTest {
        val sessionManager = mockk<SessionManager>()
        val transport = mockk<Transport>(relaxed = true)
        val request = PresentationRequest(
            docType = PresentationRequestBuilder.DEFAULT_DOC_TYPE,
            requestedElements = listOf("age_over_21"),
            nonce = byteArrayOf(1, 2, 3),
            verifierPublicKey = byteArrayOf(4, 5, 6),
        )
        val activeSession = ActiveSession(
            id = "session-1",
            request = request,
            transport = transport,
            expectedFormat = com.laurelid.mdoc.DeviceResponseFormat.COSE_SIGN1,
            transcript = byteArrayOf(7, 8, 9),
            engagementPayload = "payload".toByteArray(),
        )
        coEvery { sessionManager.startSession(any()) } returns activeSession
        every { sessionManager.handshakePayload(activeSession) } returns activeSession.engagementPayload
        coJustRun { sessionManager.cancel() }
        coEvery { sessionManager.processCiphertext(activeSession, any()) } returns VerificationResult(
            isSuccess = true,
            minimalClaims = mapOf("age_over_21" to true),
            portrait = null,
            audit = emptyList(),
        )

        val viewModel = KioskViewModel(sessionManager)
        viewModel.startSession(TransportType.QR)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertIs<KioskState.WaitingApproval>(state)
        assertEquals("session-1", state.sessionId)

        viewModel.onCiphertextReceived(byteArrayOf(0x01))
        advanceUntilIdle()
        val resultState = viewModel.state.value
        assertIs<KioskState.Verified>(resultState)
    }
}
