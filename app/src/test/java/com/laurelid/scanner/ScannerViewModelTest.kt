package com.laurelid.scanner

import com.laurelid.auth.MdocError
import com.laurelid.auth.MdocParseException
import com.laurelid.auth.ParsedMdoc
import com.laurelid.config.AdminConfig
import com.laurelid.data.VerificationResult
import com.laurelid.scanner.CredentialParser
import com.laurelid.scanner.ScannerUiState
import com.laurelid.scanner.ScannerViewModel
import com.laurelid.scanner.VerificationExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val parsedMdoc = ParsedMdoc(
        subjectDid = "did:example:123",
        docType = "org.iso.18013.5.1.mDL",
        issuer = "AZ-MVD",
        ageOver21 = true,
    )

    private val verificationResult = VerificationResult(
        success = true,
        ageOver21 = true,
        issuer = "AZ-MVD",
        subjectDid = "did:example:123",
        docType = "org.iso.18013.5.1.mDL",
        error = null,
        trustStale = false,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `submit QR payload transitions to result`() = runTest(dispatcher) {
        val viewModel = ScannerViewModel(
            credentialParser = FakeCredentialParser(parsedMdoc = parsedMdoc),
            verificationExecutor = FakeVerificationExecutor(result = verificationResult),
            dispatcher = dispatcher,
        )

        viewModel.updateConfig(AdminConfig())
        viewModel.submitQrPayload("qr")

        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ScannerUiState.Phase.Result, state.phase)
        assertEquals(verificationResult, state.result)
        assertFalse(state.isProcessing)
    }

    @Test
    fun `parser failure emits error state`() = runTest(dispatcher) {
        val viewModel = ScannerViewModel(
            credentialParser = FakeCredentialParser(throwOnQr = true),
            verificationExecutor = FakeVerificationExecutor(result = verificationResult),
            dispatcher = dispatcher,
        )

        viewModel.updateConfig(AdminConfig())
        viewModel.submitQrPayload("bad")

        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ScannerUiState.Phase.Scanning, state.phase)
        assertNull(state.result)
        assertEquals(com.laurelid.R.string.result_details_error_unknown, state.errorMessageRes)
        assertFalse(state.isProcessing)
    }

    private class FakeCredentialParser(
        private val parsedMdoc: ParsedMdoc? = null,
        private val throwOnQr: Boolean = false,
    ) : CredentialParser {
        override fun fromQr(payload: String): ParsedMdoc {
            if (throwOnQr) {
                throw MdocParseException(MdocError.Unexpected("bad payload"))
            }
            return parsedMdoc ?: error("parsedMdoc not provided")
        }

        override fun fromNfc(bytes: ByteArray): ParsedMdoc {
            return parsedMdoc ?: error("parsedMdoc not provided")
        }
    }

    private class FakeVerificationExecutor(
        private val result: VerificationResult,
    ) : VerificationExecutor {
        override suspend fun verify(
            parsedMdoc: ParsedMdoc,
            config: AdminConfig,
            demoPayloadUsed: Boolean,
        ): VerificationResult {
            return result
        }

        override fun buildClientFailureResult(parsedMdoc: ParsedMdoc): VerificationResult = result
    }
}
