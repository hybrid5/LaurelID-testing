package com.laurelid.auth.session

import com.upokecenter.cbor.CBORObject
import com.laurelid.auth.cose.CoseVerifier
import com.laurelid.auth.cose.VerifiedIssuer
import com.laurelid.auth.crypto.HpkeEngine
import com.laurelid.auth.crypto.HpkeKeyProvider
import com.laurelid.auth.crypto.SecureBytes
import com.laurelid.auth.deviceengagement.TransportType
import com.laurelid.auth.trust.TrustStore
import com.laurelid.auth.verifier.PresentationRequestBuilder
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private val hpkeEngine = mockk<HpkeEngine>()
    private val keyProvider = mockk<HpkeKeyProvider>()
    private val coseVerifier = mockk<CoseVerifier>()
    private val trustStore = mockk<TrustStore>()
    private val presentationBuilder = mockk<PresentationRequestBuilder>()
    private val webTransport = mockk<WebEngagementTransport>()
    private val nfcTransport = mockk<NfcEngagementTransport>()
    private val bleTransport = mockk<BleEngagementTransport>()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun transcriptTamperCausesSignatureFailure() = runTest {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val certificate = selfSignedCertificate(keyPair)
        val deviceResponse = deviceResponse(certificate)

        coJustRun { hpkeEngine.initRecipient(any()) }
        every { hpkeEngine.decrypt(any(), any()) } answers { SecureBytes.wrap(deviceResponse.copyOf()) }
        coJustRun { keyProvider.ensureKey(any()) }
        every { keyProvider.getPublicKeyBytes() } returns ByteArray(32) { 1 }
        every { presentationBuilder.requestedAttributes() } returns listOf("age_over_21")
        every { presentationBuilder.minimize(any()) } returns mapOf("age_over_21" to true)
        every { webTransport.stage(any()) } returns Unit
        coEvery { webTransport.start(any()) } returns EngagementSession("session", "payload".encodeToByteArray())
        coJustRun { webTransport.stop() }
        coEvery { nfcTransport.start(any()) } returns EngagementSession("nfc", ByteArray(0))
        coJustRun { nfcTransport.stop() }
        coEvery { bleTransport.start(any()) } returns EngagementSession("ble", ByteArray(0))
        coJustRun { bleTransport.stop() }
        every { trustStore.loadIacaRoots() } returns listOf(certificate)
        every { trustStore.verifyChain(any(), any(), any()) } returns true
        every { coseVerifier.verifyIssuer(any(), any(), any()) } returns VerifiedIssuer(certificate, mapOf("age_over_21" to true))

        val sessionManager = SessionManager(
            hpkeEngine,
            keyProvider,
            coseVerifier,
            trustStore,
            presentationBuilder,
            webTransport,
            nfcTransport,
            bleTransport,
            fixedClock,
        )

        val session = sessionManager.createSession(TransportType.WEB)
        val expectedTranscript = sessionManager.buildTranscript(session)
        every { coseVerifier.verifyDeviceSignature(any(), any(), any()) } answers {
            val transcript = secondArg<ByteArray>()
            transcript.contentEquals(expectedTranscript)
        }

        val success = sessionManager.decryptAndVerify(session, deviceResponse.copyOf())
        assertTrue(success.isSuccess)

        val tampered = session.copy(
            engagement = session.engagement.copy(transcript = "tampered".encodeToByteArray()),
        )
        SessionManager::class.java.getDeclaredField("activeSession").apply {
            isAccessible = true
            set(sessionManager, tampered)
        }

        val failure = sessionManager.decryptAndVerify(tampered, deviceResponse.copyOf())
        assertFalse(failure.isSuccess)
    }

    private fun deviceResponse(certificate: X509Certificate): ByteArray {
        val certArray = CBORObject.NewArray().apply { Add(certificate.encoded) }
        val map = CBORObject.NewMap().apply {
            set(CBORObject.FromObject("issuerSigned"), CBORObject.FromObject(ByteArray(1) { 0x01 }))
            set(CBORObject.FromObject("deviceSignature"), CBORObject.FromObject(ByteArray(1) { 0x02 }))
            set(CBORObject.FromObject("deviceCertificates"), certArray)
        }
        return map.EncodeToBytes()
    }

    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = Instant.parse("2023-01-01T00:00:00Z")
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=Test"),
            BigInteger.ONE,
            java.util.Date.from(now),
            java.util.Date.from(now.plusSeconds(31_536_000)),
            X500Name("CN=Test"),
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(X509CertificateHolder(holder.encoded))
    }
}
