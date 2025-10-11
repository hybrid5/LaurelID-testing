package com.laurelid.auth.session

import COSE.AlgorithmID
import COSE.CBORObject
import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laurelid.auth.cose.DefaultCoseVerifier
import com.laurelid.auth.crypto.HpkeEngine
import com.laurelid.auth.crypto.InMemoryHpkeKeyProvider
import com.laurelid.auth.trust.TrustAnchorsUnavailable
import com.laurelid.auth.trust.TrustStore
import com.laurelid.auth.verifier.PresentationRequestBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SessionManagerE2eTest {

    private lateinit var clock: Clock
    private lateinit var keyProvider: InMemoryHpkeKeyProvider
    private lateinit var hpkeEngine: FakeHpkeEngine
    private lateinit var trustStore: FakeTrustStore
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
        val privateKey = X25519PrivateKeyParameters(ByteArray(X25519PrivateKeyParameters.KEY_SIZE) { 0x01 }, 0)
        keyProvider = InMemoryHpkeKeyProvider(privateKey)
        hpkeEngine = FakeHpkeEngine()
        trustStore = FakeTrustStore()
        sessionManager = SessionManager(
            hpkeEngine,
            keyProvider,
            DefaultCoseVerifier(),
            trustStore,
            PresentationRequestBuilder(),
            WebEngagementTransport(),
            NfcEngagementTransport(NfcAdapterProvider { null }),
            clock,
        )
    }

    @Test
    fun webQrFlowEnforcesTranscriptBindingAndRejectsReplay() = runBlocking {
        val ciphertext = "valid-session-cipher".toByteArray()

        trustStore.setAnchors(emptyList())
        val session = sessionManager.createSession(TransportType.WEB)
        val transcript = sessionManager.buildTranscript(session)
        val fixture = buildDeviceResponse(transcript)
        trustStore.setAnchors(listOf(fixture.rootCert))
        hpkeEngine.enqueue(ciphertext, fixture.payload)

        val result = sessionManager.decryptAndVerify(session, ciphertext)
        assertTrue(result.isSuccess)
        assertEquals(true, result.minimalClaims[PresentationRequestBuilder.AGE_OVER_21])
        assertEquals("Vector", result.minimalClaims[PresentationRequestBuilder.GIVEN_NAME])

        val replaySession = sessionManager.createSession(TransportType.WEB)
        hpkeEngine.enqueue(ciphertext, fixture.payload)
        val replayResult = sessionManager.decryptAndVerify(replaySession, ciphertext)
        assertFalse(replayResult.isSuccess)
    }

    @Test
    fun decryptFailsWhenAnchorsMissing() = runBlocking {
        val ciphertext = "anchor-missing".toByteArray()
        val session = sessionManager.createSession(TransportType.WEB)
        val transcript = sessionManager.buildTranscript(session)
        val fixture = buildDeviceResponse(transcript)
        hpkeEngine.enqueue(ciphertext, fixture.payload)

        trustStore.clearAnchors()

        assertFailsWith<VerificationError.IssuerTrustUnavailable> {
            sessionManager.decryptAndVerify(session, ciphertext)
        }
    }

    private fun buildDeviceResponse(transcript: ByteArray): DeviceResponseFixture {
        val now = Instant.parse("2024-01-01T00:00:00Z")
        val rootKeyPair = generateKeyPair()
        val issuerKeyPair = generateKeyPair()
        val deviceKeyPair = generateKeyPair()

        val rootCert = issueCertificate(
            subject = "CN=Prod Root",
            issuer = "CN=Prod Root",
            subjectKey = rootKeyPair.public,
            signingKey = rootKeyPair.private,
            serial = BigInteger.ONE,
            notBefore = now,
            notAfter = now.plusSeconds(315360000L),
        )
        val issuerCert = issueCertificate(
            subject = "CN=Prod Issuer",
            issuer = rootCert.subjectX500Principal.name,
            subjectKey = issuerKeyPair.public,
            signingKey = rootKeyPair.private,
            serial = BigInteger.valueOf(2),
            notBefore = now,
            notAfter = now.plusSeconds(315360000L),
        )
        val deviceCert = issueCertificate(
            subject = "CN=Prod Device",
            issuer = issuerCert.subjectX500Principal.name,
            subjectKey = deviceKeyPair.public,
            signingKey = issuerKeyPair.private,
            serial = BigInteger.valueOf(3),
            notBefore = now,
            notAfter = now.plusSeconds(315360000L),
        )

        val issuerMessage = Sign1Message().apply {
            addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
            addAttribute(HeaderKeys.X5CHAIN, cborArrayOf(issuerCert.encoded, rootCert.encoded), true)
            SetContent(buildIssuerClaims().EncodeToBytes())
            sign(OneKey(issuerKeyPair.public, issuerKeyPair.private))
        }

        val transcriptDigest = MessageDigest.getInstance("SHA-256").digest(transcript)
        val deviceMessage = Sign1Message().apply {
            addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
            addAttribute(HeaderKeys.X5CHAIN, cborArrayOf(deviceCert.encoded), true)
            SetContent("device-auth".encodeToByteArray())
            setExternalBytes(this, transcriptDigest)
            sign(OneKey(deviceKeyPair.public, deviceKeyPair.private))
        }

        val payload = CBORObject.NewMap().apply {
            set(CBORObject.FromObject("issuerSigned"), CBORObject.FromObject(issuerMessage.EncodeToBytes()))
            set(CBORObject.FromObject("deviceSignature"), CBORObject.FromObject(deviceMessage.EncodeToBytes()))
            val array = CBORObject.NewArray()
            array.Add(CBORObject.FromObject(deviceCert.encoded))
            set(CBORObject.FromObject("deviceCertificates"), array)
        }.EncodeToBytes()

        return DeviceResponseFixture(payload, rootCert)
    }

    private fun buildIssuerClaims(): CBORObject = CBORObject.NewMap().apply {
        set(CBORObject.FromObject(PresentationRequestBuilder.AGE_OVER_21), CBORObject.FromObject(true))
        set(CBORObject.FromObject(PresentationRequestBuilder.GIVEN_NAME), CBORObject.FromObject("Vector"))
        set(CBORObject.FromObject(PresentationRequestBuilder.FAMILY_NAME), CBORObject.FromObject("Holder"))
        set(CBORObject.FromObject("portrait"), CBORObject.FromObject(byteArrayOf(0x01, 0x02, 0x03)))
    }

    private var keyCounter: Int = 0

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        val random = runCatching { SecureRandom.getInstance("SHA1PRNG") }.getOrElse { SecureRandom() }.apply {
            setSeed(byteArrayOf((keyCounter++).toByte()))
        }
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        return generator.generateKeyPair()
    }

    private fun issueCertificate(
        subject: String,
        issuer: String,
        subjectKey: java.security.PublicKey,
        signingKey: java.security.PrivateKey,
        serial: BigInteger,
        notBefore: Instant,
        notAfter: Instant,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuer),
            serial,
            Date.from(notBefore),
            Date.from(notAfter),
            X500Name(subject),
            subjectKey,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(signingKey)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)
    }

    private fun cborArrayOf(vararg elements: ByteArray): CBORObject {
        val array = CBORObject.NewArray()
        elements.forEach { array.Add(it) }
        return array
    }

    private fun setExternalBytes(message: Sign1Message, aad: ByteArray) {
        try {
            val method = message.javaClass.getMethod("SetExternal", ByteArray::class.java)
            method.invoke(message, aad)
        } catch (_: NoSuchMethodException) {
            val method = message.javaClass.getMethod("setExternal", ByteArray::class.java)
            method.invoke(message, aad)
        }
    }

    private class FakeHpkeEngine : HpkeEngine {
        private val payloads = mutableMapOf<String, ByteArray>()

        override suspend fun initRecipient(keyAlias: String) = Unit

        fun enqueue(ciphertext: ByteArray, plaintext: ByteArray) {
            payloads[ciphertext.toKey()] = plaintext.copyOf()
        }

        override fun decrypt(cipher: ByteArray, aad: ByteArray): ByteArray {
            val payload = payloads[cipher.toKey()] ?: error("Unexpected ciphertext for test")
            return payload.copyOf()
        }

        private fun ByteArray.toKey(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    }

    private class FakeTrustStore : TrustStore {
        private var anchors: List<X509Certificate>? = null

        fun setAnchors(list: List<X509Certificate>) {
            anchors = list.toList()
        }

        fun clearAnchors() {
            anchors = null
        }

        override fun loadIacaRoots(): List<X509Certificate> =
            anchors ?: throw TrustAnchorsUnavailable("No production anchors configured")

        override fun verifyChain(
            chain: List<X509Certificate>,
            anchors: List<X509Certificate>,
            at: Instant,
        ): Boolean {
            if (chain.isEmpty() || anchors.isEmpty()) return false
            val trustAnchors = anchors.map { TrustAnchor(it, null) }.toSet()
            val cf = CertificateFactory.getInstance("X.509")
            val certPath = cf.generateCertPath(chain)
            val params = PKIXParameters(trustAnchors).apply {
                isRevocationEnabled = false
                date = Date.from(at)
            }
            return runCatching {
                CertPathValidator.getInstance("PKIX").validate(certPath, params)
                true
            }.getOrElse { false }
        }
    }

    private data class DeviceResponseFixture(
        val payload: ByteArray,
        val rootCert: X509Certificate,
    )
}
