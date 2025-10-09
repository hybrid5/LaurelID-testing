package com.laurelid.auth.cose

import COSE.AlgorithmID
import COSE.CBORObject
import COSE.HeaderKeys
import COSE.OneKey
import COSE.Sign1Message
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.BeforeClass
import org.junit.Test

class CoseVerifierTest {

    @Test
    fun verifiesIssuerAndExtractsClaims() {
        val keyPair = generateKeyPair()
        val certificate = selfSignedCertificate(keyPair)
        val message = Sign1Message()
        message.addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
        message.addAttribute(HeaderKeys.X5CHAIN, CBORObject.NewArray().apply {
            Add(certificate.encoded)
        }, true)
        message.SetContent(mapOf("age_over_21" to true).toCbor())
        message.sign(OneKey(keyPair.public, keyPair.private))

        val verifier = DefaultCoseVerifier()
        val verified = verifier.verifyIssuer(message.EncodeToBytes(), listOf(certificate))
        assertTrue(verified.claims["age_over_21"] as Boolean)

        val minimal = verifier.extractAttributes(verified, listOf("age_over_21"))
        assertEquals(true, minimal["age_over_21"])
    }

    private fun Map<String, Any?>.toCbor(): ByteArray {
        val obj = CBORObject.NewMap()
        for ((key, value) in this) {
            obj[CBORObject.FromObject(key)] = CBORObject.FromObject(value)
        }
        return obj.EncodeToBytes()
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            Security.addProvider(BouncyCastleProvider())
        }

        private fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(256)
            return generator.generateKeyPair()
        }

        private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
            val now = Date()
            val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
                X500Name("CN=Test"),
                BigInteger.ONE,
                now,
                Date(now.time + 86_400_000L),
                X500Name("CN=Test"),
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
            val holder: X509CertificateHolder = builder.build(signer)
            return JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(holder)
        }
    }
}

