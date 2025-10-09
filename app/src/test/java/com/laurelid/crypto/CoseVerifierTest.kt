package com.laurelid.crypto

import COSE.AlgorithmID
import COSE.CBORObject
import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class CoseVerifierTest {

    private val verifier = DefaultCoseVerifier()
    private lateinit var keyPair: KeyPair
    private lateinit var certificate: X509Certificate

    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        keyPair = generateKeyPair()
        certificate = selfSignedCertificate(keyPair)
    }

    @Test
    fun `verify issuer fails when no anchors available`() {
        val message = createSignedMessage()
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { verifier.verifyIssuer(message, emptyList()) }
        }
        assertEquals("No trust anchors configured", error.message)
    }

    @Test
    fun `verify issuer succeeds with trusted root`() {
        val message = createSignedMessage()
        val result = runBlocking { verifier.verifyIssuer(message, listOf(certificate)) }
        assertTrue(result.claims.isEmpty())
        assertEquals(certificate, result.signerCert)
    }

    @Test
    fun `verify device signature fails with invalid payload`() {
        val chain = loadCertificates("mdoc/certs/apple_device_chain.pem")
        val result = kotlin.runCatching {
            verifier.verifyDeviceSignature(ByteArray(32), ByteArray(16), chain)
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `extract attributes filters map`() {
        val issuer = VerifiedIssuer(
            signerCert = loadCertificates("mdoc/certs/test_issuer.pem").first(),
            claims = mapOf("age_over_21" to true, "given_name" to "Ada", "extra" to "ignored"),
        )
        val attributes = verifier.extractAttributes(issuer, listOf("age_over_21", "given_name"))
        assertTrue(attributes.containsKey("age_over_21"))
        assertFalse(attributes.containsKey("extra"))
    }

    private fun loadCertificates(path: String): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        val stream = javaClass.classLoader!!.getResourceAsStream(path)!!
        return stream.use { input -> cf.generateCertificates(input).map { it as X509Certificate } }
    }

    private fun createSignedMessage(): ByteArray {
        val message = Sign1Message()
        message.addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
        message.addAttribute(HeaderKeys.X5CHAIN, CBORObject.NewArray().apply {
            Add(certificate.encoded)
        }, true)
        message.SetContent(CBORObject.NewMap().EncodeToBytes())
        message.sign(OneKey(keyPair.public, keyPair.private))
        return message.EncodeToBytes()
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        return generator.generateKeyPair()
    }

    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = Date()
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            X500Name("CN=Test Root"),
            BigInteger.ONE,
            now,
            Date(now.time + 86_400_000L),
            X500Name("CN=Test Root"),
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        val holder: X509CertificateHolder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)
    }
}
