package com.laurelid.auth

import COSE.AlgorithmID
import COSE.Attribute
import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import com.upokecenter.cbor.CBORObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.io.path.createTempDirectory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object TestCredentialFixtures {
    const val AGE_NAMESPACE: String = "org.iso.18013.5.1"
    const val AGE_ELEMENT: String = "age_over_21"

    data class CredentialScenario(
        val parsed: ParsedMdoc,
        val issuer: String,
        val certificateBase64: String,
    )

    fun createScenario(
        clock: Clock,
        validUntil: Instant,
        tamperDeviceData: Boolean = false,
    ): CredentialScenario {
        val issuerId = "AZ-MVD"
        val docType = "org.iso.18013.5.1.mDL"
        val keyPair = generateKeyPair()
        val certificate = createCertificate(clock, issuerId, keyPair)

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

    fun <T> withTempDir(prefix: String = "trust-list-test", block: (File) -> T): T {
        val directory = createTempDirectory(prefix).toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256, SecureRandom())
        return generator.generateKeyPair()
    }

    private fun createCertificate(clock: Clock, issuer: String, keyPair: KeyPair): X509Certificate {
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
}
