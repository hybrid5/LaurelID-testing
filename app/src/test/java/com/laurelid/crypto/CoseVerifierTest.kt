package com.laurelid.crypto

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoseVerifierTest {

    private val verifier = DefaultCoseVerifier()

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
}
