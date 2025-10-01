package com.laurelid.auth

import COSE.Attribute
import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import COSE.AlgorithmID
import com.laurelid.network.TrustListApi
import com.laurelid.network.TrustListRepository
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import com.upokecenter.cbor.CBORObject

class WalletVerifierTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `valid credential passes verification`() = runBlocking {
        val scenario = createScenario(validUntil = clock.instant().plus(1, ChronoUnit.DAYS))
        withTempDir { dir ->
            val repository = TrustListRepository(FakeTrustListApi(mapOf(scenario.issuer to scenario.certificateBase64)), dir)
            val verifier = WalletVerifier(repository, clock)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertTrue(result.success)
            assertTrue(result.ageOver21 == true)
            assertEquals(scenario.issuer, result.issuer)
            assertEquals(null, result.error)
        }
    }

    @Test
    fun `expired credential fails closed`() = runBlocking {
        val scenario = createScenario(validUntil = clock.instant().minus(1, ChronoUnit.HOURS))
        withTempDir { dir ->
            val repository = TrustListRepository(FakeTrustListApi(mapOf(scenario.issuer to scenario.certificateBase64)), dir)
            val verifier = WalletVerifier(repository, clock)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_DOC_EXPIRED, result.error)
        }
    }

    @Test
    fun `tampered device data is rejected`() = runBlocking {
        val scenario = createScenario(validUntil = clock.instant().plus(1, ChronoUnit.DAYS), tamperDeviceData = true)
        withTempDir { dir ->
            val repository = TrustListRepository(FakeTrustListApi(mapOf(scenario.issuer to scenario.certificateBase64)), dir)
            val verifier = WalletVerifier(repository, clock)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_DEVICE_DATA_TAMPERED, result.error)
        }
    }

    @Test
    fun `issuer not in trust list is rejected`() = runBlocking {
        val scenario = createScenario(validUntil = clock.instant().plus(1, ChronoUnit.DAYS))
        withTempDir { dir ->
            val repository = TrustListRepository(FakeTrustListApi(mapOf("OtherIssuer" to scenario.certificateBase64)), dir)
            val verifier = WalletVerifier(repository, clock)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_UNTRUSTED_ISSUER, result.error)
        }
    }

    private fun createScenario(
        validUntil: Instant,
        tamperDeviceData: Boolean = false
    ): CredentialScenario {
        val issuerId = "AZ-MVD"
        val docType = "org.iso.18013.5.1.mDL"
        val keyPair = generateKeyPair()
        val certificate = createCertificate(issuerId, keyPair)

        val ageValue = CBORObject.FromObject(true).EncodeToBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(ageValue)

        val valueDigests = CBORObject.NewMap().apply {
            val namespaceMap = CBORObject.NewMap().apply {
                Add(AGE_ELEMENT, CBORObject.FromObject(digest))
            }
            Add(AGE_NAMESPACE, namespaceMap)
        }

        val validityInfo = CBORObject.NewMap().apply {
            Add("validFrom", CBORObject.FromObject(clock.instant().minus(1, ChronoUnit.DAYS).epochSecond))
            Add("validUntil", CBORObject.FromObject(validUntil.epochSecond))
        }

        val mso = CBORObject.NewMap().apply {
            Add("docType", docType)
            Add("issuer", issuerId)
            Add("digestAlgorithm", "SHA-256")
            Add("valueDigests", valueDigests)
            Add("validityInfo", validityInfo)
        }

        val sign1 = Sign1Message().apply {
            addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), Attribute.PROTECTED)
            SetContent(mso.EncodeToBytes())
            sign(OneKey(keyPair.private, keyPair.public))
        }

        val deviceValue = if (tamperDeviceData) {
            CBORObject.FromObject(false).EncodeToBytes()
        } else {
            ageValue
        }

        val parsed = ParsedMdoc(
            subjectDid = "did:example:${UUID.randomUUID()}",
            docType = docType,
            issuer = issuerId,
            ageOver21 = !tamperDeviceData,
            issuerAuth = sign1.EncodeToBytes(),
            deviceSignedEntries = mapOf(
                AGE_NAMESPACE to mapOf(AGE_ELEMENT to deviceValue)
            )
        )

        val certificateBase64 = Base64.getEncoder().encodeToString(certificate.encoded)

        return CredentialScenario(parsed, issuerId, certificateBase64)
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256, SecureRandom())
        return generator.generateKeyPair()
    }

    private fun createCertificate(issuer: String, keyPair: KeyPair): java.security.cert.X509Certificate {
        val now = clock.instant()
        val contentSigner = JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)

        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"),
            BigInteger.valueOf(now.epochSecond),
            Date.from(now.minus(1, ChronoUnit.DAYS)),
            Date.from(now.plus(365, ChronoUnit.DAYS)),
            X500Name("CN=$issuer"),
            keyPair.public
        )

        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(contentSigner))
    }

    private class FakeTrustListApi(
        private val payload: Map<String, String>
    ) : TrustListApi {
        override suspend fun getTrustList(): Map<String, String> = payload
    }

    private data class CredentialScenario(
        val parsed: ParsedMdoc,
        val issuer: String,
        val certificateBase64: String
    )

    companion object {
        private const val AGE_NAMESPACE = "org.iso.18013.5.1"
        private const val AGE_ELEMENT = "age_over_21"
    }

    private fun <T> withTempDir(block: (java.io.File) -> T): T {
        val directory = createTempDirectory("trust-list-test").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
