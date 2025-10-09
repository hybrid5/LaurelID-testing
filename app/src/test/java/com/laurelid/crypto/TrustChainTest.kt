package com.laurelid.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trustStore = ResourceTrustStore(context)

    @Test
    fun `load roots from resources`() {
        val roots = trustStore.loadIacaRoots()
        assertTrue(roots.isNotEmpty())
    }

    @Test
    fun `verify chain succeeds with bundled anchors`() {
        val issuer = loadCertificates("mdoc/certs/test_issuer.pem")
        val anchors = trustStore.loadIacaRoots()
        assertTrue(trustStore.verifyChain(issuer, anchors, Instant.now()))
    }

    @Test
    fun `verify chain fails with empty anchors`() {
        val issuer = loadCertificates("mdoc/certs/test_issuer.pem")
        assertFalse(trustStore.verifyChain(issuer, emptyList(), Instant.now()))
    }

    private fun loadCertificates(path: String): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        val stream = javaClass.classLoader!!.getResourceAsStream(path)!!
        return stream.use { input -> cf.generateCertificates(input).map { it as X509Certificate } }
    }
}
