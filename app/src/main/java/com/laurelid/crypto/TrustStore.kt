package com.laurelid.crypto

import android.content.Context
import com.laurelid.R
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads verifier trust anchors and validates certificate chains (ISO/IEC 18013-5 §9.1.3). 【ISO18013-5§9.1.3】
 */
interface TrustStore {
    fun loadIacaRoots(): List<X509Certificate>
    fun verifyChain(chain: List<X509Certificate>, anchors: List<X509Certificate>, at: Instant): Boolean
}

@Singleton
class ResourceTrustStore @Inject constructor(
    private val context: Context,
) : TrustStore {

    private val cache = lazy { loadFromResources() }

    override fun loadIacaRoots(): List<X509Certificate> = cache.value

    override fun verifyChain(
        chain: List<X509Certificate>,
        anchors: List<X509Certificate>,
        at: Instant,
    ): Boolean {
        if (chain.isEmpty()) return false
        val trustAnchors = anchors.map { TrustAnchor(it, null) }.toSet()
        if (trustAnchors.isEmpty()) return false
        val cf = CertificateFactory.getInstance("X.509")
        val certPath = cf.generateCertPath(chain)
        val params = PKIXParameters(trustAnchors).apply {
            isRevocationEnabled = false
            date = java.util.Date.from(at)
        }
        val validator = CertPathValidator.getInstance("PKIX")
        return runCatching { validator.validate(certPath, params); true }.getOrElse { false }
    }

    private fun loadFromResources(): List<X509Certificate> {
        val certificates = mutableListOf<X509Certificate>()
        val factory = CertificateFactory.getInstance("X.509")
        val classLoader = ResourceTrustStore::class.java.classLoader
        for (path in RESOURCE_PATHS) {
            val stream = classLoader?.getResourceAsStream(path)
                ?: context.resources.openRawResource(R.raw.iaca_roots)
            stream.use { certificates += factory.generateCertificates(it.buffered()).map { cert -> cert as X509Certificate } }
        }
        return certificates.distinctBy { it.serialNumber }
    }

    private fun InputStream.buffered(): InputStream = BufferedInputStream(this)

    companion object {
        private val RESOURCE_PATHS = listOf(
            "trust/iaca/mdoc_iaca_root.pem",
        )
    }
}
