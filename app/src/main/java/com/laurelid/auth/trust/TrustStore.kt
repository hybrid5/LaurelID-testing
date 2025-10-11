package com.laurelid.auth.trust

import com.laurelid.trust.TrustBootstrap
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Loads IACA root certificates and validates issuer/device chains. */
interface TrustStore {
    fun loadIacaRoots(): List<X509Certificate>
    fun verifyChain(chain: List<X509Certificate>, anchors: List<X509Certificate>, at: Instant): Boolean
}

/** Thrown when verifier trust anchors are missing or invalid. */
class TrustAnchorsUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Singleton
class ResourceTrustStore @Inject constructor(
    private val bootstrap: TrustBootstrap,
) : TrustStore {

    override fun loadIacaRoots(): List<X509Certificate> {
        val anchors = bootstrap.anchors()
        if (anchors.isNotEmpty()) return anchors
        val refreshed = bootstrap.refreshAnchors()
        if (refreshed.isEmpty()) {
            throw TrustAnchorsUnavailable("No production IACA anchors available")
        }
        return refreshed
    }

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
}
