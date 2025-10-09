package com.laurelid.verifier.crypto

import com.augustcellars.cbor.CBORObject
import com.augustcellars.cose.AlgorithmID
import com.augustcellars.cose.HeaderKeys
import com.augustcellars.cose.OneKey
import com.augustcellars.cose.Sign1Message
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.BeforeClass
import org.junit.Test

class CoseVerifierImplTest {

    @Test
    fun verifiesIssuerAndDeviceSignature() {
        val (keyPair, certificate) = generateCertificate()
        val payload = CBORObject.NewMap().apply {
            set(CBORObject.FromObject("age_over_21"), CBORObject.FromObject(true))
            set(CBORObject.FromObject("given_name"), CBORObject.FromObject("Jane"))
        }.EncodeToBytes()

        val issuerMessage = Sign1Message().apply {
            addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
            val x5Chain = CBORObject.NewArray().apply { Add(certificate.encoded) }
            addAttribute(HeaderKeys.X5CHAIN, x5Chain, true)
            SetContent(payload)
            sign(OneKey(keyPair.private, keyPair.public))
        }

        val deviceSig = Sign1Message().apply {
            addAttribute(HeaderKeys.Algorithm, AlgorithmID.ECDSA_256.AsCBOR(), true)
            val external = MessageDigest.getInstance("SHA-256").digest("transcript".toByteArray())
            SetExternal(external)
            SetContent(ByteArray(0))
            sign(OneKey(keyPair.private, keyPair.public))
        }

        val verifier = CoseVerifierImpl()
        val verifiedIssuer = verifier.verifyIssuer(issuerMessage.EncodeToBytes(), listOf(certificate))
        assertTrue(verifiedIssuer.claims["age_over_21"] as Boolean)
        assertEquals("Jane", verifiedIssuer.claims["given_name"])

        val deviceValid = verifier.verifyDeviceSignature(
            deviceSig.EncodeToBytes(),
            "transcript".toByteArray(),
            listOf(certificate),
        )
        assertTrue(deviceValid)
    }

    private fun generateCertificate(): Pair<KeyPair, X509Certificate> {
        val keyPairGen = KeyPairGenerator.getInstance("EC", PROVIDER)
        keyPairGen.initialize(256)
        val keyPair = keyPairGen.generateKeyPair()
        val now = Date()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=Test Root"),
            BigInteger.ONE,
            now,
            Date(now.time + 3_600_000L),
            X500Name("CN=Test Root"),
            keyPair.public,
        )
        val signer: ContentSigner = JcaContentSignerBuilder("SHA256withECDSA").setProvider(PROVIDER).build(keyPair.private)
        val holder = builder.build(signer)
        val certificate = JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(holder)
        certificate.verify(keyPair.public)
        return keyPair to certificate
    }

    companion object {
        private const val PROVIDER = "BC"

        @JvmStatic
        @BeforeClass
        fun setupProvider() {
            if (Security.getProvider(PROVIDER) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}
