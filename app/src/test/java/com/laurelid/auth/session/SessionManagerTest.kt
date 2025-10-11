package com.laurelid.auth.session

import com.laurelid.auth.cose.CoseVerifier
import com.laurelid.auth.cose.VerifiedIssuer
import com.laurelid.auth.crypto.HpkeEngine
import com.laurelid.auth.crypto.HpkeKeyProvider
import com.laurelid.auth.crypto.HpkePrivateKeyHandle
import com.laurelid.auth.deviceengagement.TransportType
import com.laurelid.auth.trust.TrustStore
import com.laurelid.auth.verifier.PresentationRequestBuilder
import com.upokecenter.cbor.CBORObject
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    @Test
    fun `createSession initializes HPKE and stages web transport`() = runTest {
        val keyProvider = FakeHpkeKeyProvider()
        val hpkeEngine = FakeHpkeEngine()
        val coseVerifier = RecordingCoseVerifier()
        val trustStore = FakeTrustStore()
        val presentationBuilder = PresentationRequestBuilder()
        val webTransport = mockk<WebEngagementTransport>(relaxed = true)
        val nfcTransport = mockk<NfcEngagementTransport>(relaxed = true)
        val transcript = "engagement".encodeToByteArray()

        coEvery { webTransport.start(any()) } returns EngagementSession("session-1", transcript)

        val manager = SessionManager(
            hpkeEngine,
            keyProvider,
            coseVerifier,
            trustStore,
            presentationBuilder,
            webTransport,
            nfcTransport,
            Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
        )

        val session = manager.createSession()

        assertEquals(HpkeEngine.DEFAULT_KEY_ALIAS, keyProvider.lastEnsured)
        assertEquals(HpkeEngine.DEFAULT_KEY_ALIAS, hpkeEngine.initializedAlias)
        assertEquals(32, session.request.nonce.size)
        assertContentEquals(keyProvider.publicKey, session.request.verifierPublicKey)
        assertContentEquals(transcript, session.engagement.transcript)
        assertEquals(TransportType.WEB, session.transport.type)
    }

    @Test
    fun `decryptAndVerify returns minimized claims and audit`() = runTest {
        val keyProvider = FakeHpkeKeyProvider()
        val hpkeEngine = FakeHpkeEngine()
        val signerCert = mockk<X509Certificate>()
        every { signerCert.subjectX500Principal } returns X500Principal("CN=Verifier Issuer")
        every { signerCert.publicKey } returns mockk(relaxed = true)
        val claims = mapOf(
            "given_name" to "Ada",
            "family_name" to "Lovelace",
            "age_over_21" to true,
            "portrait" to byteArrayOf(0x01, 0x02),
            "extra" to "ignored",
        )
        val coseVerifier = RecordingCoseVerifier(
            VerifiedIssuer(signerCert, claims)
        )
        val trustStore = FakeTrustStore()
        val presentationBuilder = PresentationRequestBuilder()
        val transcript = "qr-handshake".encodeToByteArray()
        val ciphertext = byteArrayOf(0x42)
        val issuerSigned = byteArrayOf(0x10, 0x20)
        val deviceSignature = byteArrayOf(0x30, 0x40)
        hpkeEngine.plaintext = buildDeviceResponse(issuerSigned, deviceSignature, emptyList())

        val webTransport = mockk<WebEngagementTransport>(relaxed = true)
        coEvery { webTransport.start(any()) } returns EngagementSession("session-2", transcript)
        val nfcTransport = mockk<NfcEngagementTransport>(relaxed = true)

        val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
        val manager = SessionManager(
            hpkeEngine,
            keyProvider,
            coseVerifier,
            trustStore,
            presentationBuilder,
            webTransport,
            nfcTransport,
            clock,
        )

        val session = manager.createSession()
        val result = manager.decryptAndVerify(session, ciphertext)

        assertTrue(result.isSuccess)
        assertEquals(setOf("given_name", "family_name", "age_over_21", "portrait"), result.minimalClaims.keys)
        assertContentEquals(byteArrayOf(0x01, 0x02), result.portrait!!)
        assertTrue(result.audit.any { it.contains("issuer=CN=Verifier Issuer") })

        assertContentEquals(issuerSigned, coseVerifier.lastIssuerPayload)
        assertContentEquals(deviceSignature, coseVerifier.lastDeviceSignature)
        val expectedTranscript = manager.buildTranscript(session)
        assertContentEquals(expectedTranscript, coseVerifier.lastTranscript)
        assertEquals(emptyList<X509Certificate>(), coseVerifier.lastDeviceChain)
        assertEquals(1, hpkeEngine.decryptCalls.size)
        assertContentEquals(ciphertext, hpkeEngine.decryptCalls[0].first)
        assertContentEquals(transcript, hpkeEngine.decryptCalls[0].second)
    }

    private class FakeHpkeKeyProvider : HpkeKeyProvider {
        var lastEnsured: String? = null
        val publicKey: ByteArray = ByteArray(32) { 0x11 }

        override suspend fun ensureKey(alias: String) {
            lastEnsured = alias
        }

        override fun getPublicKeyBytes(): ByteArray = publicKey

        override fun getPrivateKeyHandle(): HpkePrivateKeyHandle =
            HpkePrivateKeyHandle.Debug(X25519PrivateKeyParameters(ByteArray(32), 0))

        override fun installDebugKey(alias: String, privateKey: ByteArray) = error("not used in tests")
    }

    private class FakeHpkeEngine : HpkeEngine {
        var initializedAlias: String? = null
        var plaintext: ByteArray = byteArrayOf()
        val decryptCalls = mutableListOf<Pair<ByteArray, ByteArray>>()

        override suspend fun initRecipient(keyAlias: String) {
            initializedAlias = keyAlias
        }

        override fun decrypt(cipher: ByteArray, aad: ByteArray): ByteArray {
            decryptCalls += cipher to aad
            return plaintext
        }
    }

    private class RecordingCoseVerifier(
        private val issuer: VerifiedIssuer = VerifiedIssuer(mockk(relaxed = true), emptyMap()),
    ) : CoseVerifier {
        var lastIssuerPayload: ByteArray = byteArrayOf()
        var lastDeviceSignature: ByteArray = byteArrayOf()
        var lastTranscript: ByteArray = byteArrayOf()
        var lastDeviceChain: List<X509Certificate> = emptyList()

        override fun verifyIssuer(
            payload: ByteArray,
            trustAnchors: List<X509Certificate>,
            at: Instant,
        ): VerifiedIssuer {
            lastIssuerPayload = payload
            return issuer
        }

        override fun verifyDeviceSignature(
            deviceSignature: ByteArray,
            transcript: ByteArray,
            deviceChain: List<X509Certificate>,
        ): Boolean {
            lastDeviceSignature = deviceSignature
            lastTranscript = transcript
            lastDeviceChain = deviceChain
            return true
        }

        override fun extractAttributes(issuer: VerifiedIssuer, requested: Collection<String>): Map<String, Any?> =
            issuer.claims.filterKeys { it in requested }
    }

    private class FakeTrustStore : TrustStore {
        override fun loadIacaRoots(): List<X509Certificate> = emptyList()

        override fun verifyChain(
            chain: List<X509Certificate>,
            anchors: List<X509Certificate>,
            at: Instant,
        ): Boolean = true
    }

    private fun buildDeviceResponse(
        issuerSigned: ByteArray,
        deviceSignature: ByteArray,
        certificates: List<ByteArray>,
    ): ByteArray {
        val certArray = CBORObject.NewArray().apply {
            certificates.forEach { Add(CBORObject.FromObject(it)) }
        }
        return CBORObject.NewMap().apply {
            set(CBORObject.FromObject("issuerSigned"), CBORObject.FromObject(issuerSigned))
            set(CBORObject.FromObject("deviceSignature"), CBORObject.FromObject(deviceSignature))
            set(CBORObject.FromObject("deviceCertificates"), certArray)
        }.EncodeToBytes()
    }
}
