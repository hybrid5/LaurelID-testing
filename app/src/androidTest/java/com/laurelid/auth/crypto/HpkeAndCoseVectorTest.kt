package com.laurelid.auth.crypto

import COSE.AlgorithmID
import COSE.CBORObject
import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStreamReader
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HpkeAndCoseVectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun installBcProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun hpkeVectorDecryptsWithKeystoreHandle() = runBlocking {
        val alias = "hpke-vector-${System.currentTimeMillis()}"
        val keyProvider = AndroidHpkeKeyProvider(context)
        keyProvider.ensureKey(alias)
        val engine = BouncyCastleHpkeEngine(keyProvider)
        engine.initRecipient(alias)
        val handle = keyProvider.getPrivateKeyHandle()
        assertTrue(handle is HpkePrivateKeyHandle.AndroidKeyStore)

        val vector = loadHpkeVector()
        val hpke = HPKE.create(
            HPKE.KEM_X25519_HKDF_SHA256,
            HPKE.KDF_HKDF_SHA256,
            HPKE.AEAD_AES_GCM_256,
        )
        val recipient = X25519PublicKeyParameters(keyProvider.getPublicKeyBytes(), 0)
        val ephemeralPrivate = X25519PrivateKeyParameters(vector.ephemeralPrivateKey, 0)
        val ephemeralPublic = ephemeralPrivate.generatePublicKey()
        val sender = hpke.setupBaseS(
            recipient,
            vector.info.encodeToByteArray(),
            AsymmetricCipherKeyPair(ephemeralPublic, ephemeralPrivate),
        )
        val ciphertext = sender.seal(vector.plaintext, vector.aad)
        val envelope = HpkeEnvelope(sender.encapsulation, ciphertext)

        val decrypted = engine.decrypt(envelope.toByteArray(), vector.aad)
        assertContentEquals(vector.plaintext.toList(), decrypted.toList())
    }

    @Test
    fun coseVectorValidatesIssuerAndDeviceSignature() {
        val vector = loadCoseVector()
        val fixtures = buildCoseFixtures(vector)
        val verifier = com.laurelid.auth.cose.DefaultCoseVerifier()

        val issuer = verifier.verifyIssuer(
            fixtures.issuerMessage.EncodeToBytes(),
            listOf(fixtures.rootCert),
            Instant.now(),
        )
        assertEquals("Vector", issuer.claims["given_name"])
        assertEquals("Holder", issuer.claims["family_name"])
        assertEquals(true, issuer.claims["age_over_21"])
        assertContentEquals(vector.portrait.toList(), issuer.claims["portrait"] as ByteArray)

        val valid = verifier.verifyDeviceSignature(
            fixtures.deviceMessage.EncodeToBytes(),
            vector.transcript,
            listOf(fixtures.deviceCert),
        )
        assertTrue(valid)
    }

    private fun loadHpkeVector(): HpkeVectorData {
        context.assets.open("crypto/hpke_vector.json").use { stream ->
            val json = JSONObject(InputStreamReader(stream).readText())
            val decoder = Base64.getDecoder()
            return HpkeVectorData(
                info = json.getString("info"),
                aad = decoder.decode(json.getString("aad_b64")),
                plaintext = decoder.decode(json.getString("plaintext_b64")),
                ephemeralPrivateKey = decoder.decode(json.getString("ephemeral_private_key_b64")),
            )
        }
    }

    private fun loadCoseVector(): CoseVectorData {
        context.assets.open("crypto/cose_vector.json").use { stream ->
            val json = JSONObject(InputStreamReader(stream).readText())
            val decoder = Base64.getDecoder()
            val claimsJson = json.getJSONObject("claims")
            val claims = mutableMapOf<String, Any?>()
            for (key in claimsJson.keys()) {
                val value = claimsJson.get(key)
                claims[key] = when (value) {
                    is Boolean -> value
                    is Number -> value
                    is String -> if (key == "portrait") decoder.decode(value) else value
                    else -> value
                }
            }
            return CoseVectorData(
                rootSeed = decoder.decode(json.getString("root_seed_b64")),
                issuerSeed = decoder.decode(json.getString("issuer_seed_b64")),
                deviceSeed = decoder.decode(json.getString("device_seed_b64")),
                transcript = decoder.decode(json.getString("transcript_b64")),
                claims = claims,
                portrait = claims["portrait"] as ByteArray,
            )
        }
    }

    private fun buildCoseFixtures(vector: CoseVectorData): CoseFixtures {
        val rootKeyPair = generateDeterministicKeyPair(vector.rootSeed)
        val issuerKeyPair = generateDeterministicKeyPair(vector.issuerSeed)
        val deviceKeyPair = generateDeterministicKeyPair(vector.deviceSeed)

        val now = Instant.parse("2024-01-01T00:00:00Z")
        val validitySeconds = 315360000L // ~10 years
        val rootCert = issueCertificate(
            subject = "CN=Vector Root",
            issuer = "CN=Vector Root",
            subjectKey = rootKeyPair.public,
            signingKey = rootKeyPair.private,
            serial = BigInteger.ONE,
            notBefore = now,
            notAfter = now.plusSeconds(validitySeconds),
        )
        val issuerCert = issueCertificate(
            subject = "CN=Vector Issuer",
            issuer = rootCert.subjectX500Principal.name,
            subjectKey = issuerKeyPair.public,
            signingKey = rootKeyPair.private,
            serial = BigInteger.valueOf(2),
            notBefore = now,
            notAfter = now.plusSeconds(validitySeconds),
        )
        val deviceCert = issueCertificate(
            subject = "CN=Vector Device",
            issuer = issuerCert.subjectX500Principal.name,
            subjectKey = deviceKeyPair.public,
            signingKey = issuerKeyPair.private,
            serial = BigInteger.valueOf(3),
            notBefore = now,
            notAfter = now.plusSeconds(validitySeconds),
        )

        val issuerMessage = Sign1Message().apply {
            addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
            addAttribute(HeaderKeys.X5CHAIN, cborArrayOf(issuerCert.encoded, rootCert.encoded), true)
            SetContent(claimsToCbor(vector.claims))
            sign(OneKey(issuerKeyPair.public, issuerKeyPair.private))
        }

        val transcriptDigest = MessageDigest.getInstance("SHA-256").digest(vector.transcript)
        val deviceMessage = Sign1Message().apply {
            addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
            addAttribute(HeaderKeys.X5CHAIN, cborArrayOf(deviceCert.encoded), true)
            SetContent("device-auth".encodeToByteArray())
            setExternalBytes(this, transcriptDigest)
            sign(OneKey(deviceKeyPair.public, deviceKeyPair.private))
        }

        return CoseFixtures(issuerMessage, deviceMessage, rootCert, deviceCert)
    }

    private fun generateDeterministicKeyPair(seed: ByteArray): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        val random = SecureRandom.getInstance("SHA1PRNG")
        random.setSeed(seed)
        generator.initialize(256, random)
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
            java.util.Date.from(notBefore),
            java.util.Date.from(notAfter),
            X500Name(subject),
            subjectKey,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA")
            .build(signingKey)
        val holder: X509CertificateHolder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)
    }

    private fun cborArrayOf(vararg elements: ByteArray): CBORObject {
        val array = CBORObject.NewArray()
        elements.forEach { array.Add(it) }
        return array
    }

    private fun claimsToCbor(claims: Map<String, Any?>): ByteArray {
        val obj = CBORObject.NewMap()
        for ((key, value) in claims) {
            val cborValue = when (value) {
                null -> CBORObject.Null
                is Boolean -> CBORObject.FromObject(value)
                is Number -> CBORObject.FromObject(value)
                is ByteArray -> CBORObject.FromObject(value)
                else -> CBORObject.FromObject(value.toString())
            }
            obj[CBORObject.FromObject(key)] = cborValue
        }
        return obj.EncodeToBytes()
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

    private data class HpkeVectorData(
        val info: String,
        val aad: ByteArray,
        val plaintext: ByteArray,
        val ephemeralPrivateKey: ByteArray,
    )

    private data class CoseVectorData(
        val rootSeed: ByteArray,
        val issuerSeed: ByteArray,
        val deviceSeed: ByteArray,
        val transcript: ByteArray,
        val claims: Map<String, Any?>,
        val portrait: ByteArray,
    )

    private data class CoseFixtures(
        val issuerMessage: Sign1Message,
        val deviceMessage: Sign1Message,
        val rootCert: X509Certificate,
        val deviceCert: X509Certificate,
    )
}
