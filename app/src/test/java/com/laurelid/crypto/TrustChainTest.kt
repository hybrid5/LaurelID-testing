package com.laurelid.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trustStore = ResourceTrustStore(context)

    @Test
    fun `verify AZ root is trusted`() {
        val roots = trustStore.loadIacaRoots()
        val azRoot = roots.first { it.subjectX500Principal.name.contains("AZ prod IACA certificate") }
        assertTrue(trustStore.verifyChain(listOf(azRoot), listOf(azRoot), Instant.now()))
    }

    @Test
    fun `verify chain fails with empty anchors`() {
        val issuer = loadCertificates("mdoc/certs/test_issuer.pem")
        assertFalse(trustStore.verifyChain(issuer, emptyList(), Instant.now()))
    }

    private fun loadCertificates(path: String): List<java.security.cert.X509Certificate> {
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val stream = javaClass.classLoader!!.getResourceAsStream(path)!!
        return stream.use { input -> cf.generateCertificates(input).map { it as java.security.cert.X509Certificate } }
    }
}
