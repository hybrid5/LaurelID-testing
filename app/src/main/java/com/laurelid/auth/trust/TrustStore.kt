package com.laurelid.auth.trust

import android.content.Context
import android.content.res.Resources
import com.laurelid.R
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertificateExpiredException
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Loads IACA root certificates and validates issuer/device chains. */
interface TrustStore {
    @Throws(TrustAnchorsUnavailable::class)
    fun loadIacaRoots(): List<X509Certificate>

    fun verifyChain(chain: List<X509Certificate>, anchors: List<X509Certificate>, at: Instant): Boolean
}

/** Thrown when verifier trust anchors are missing, stale, or unrecognised. */
class TrustAnchorsUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Singleton
class ResourceTrustStore @Inject constructor(
    private val context: Context,
    private val clock: Clock,
) : TrustStore {

    private val cache = lazy { loadAnchorsFromBundle() }

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

    private fun loadAnchorsFromBundle(): List<X509Certificate> {
        val now = clock.instant()
        val certificates = readCertificates(context.resources)
        if (certificates.isEmpty()) {
            throw TrustAnchorsUnavailable("IACA trust bundle empty")
        }
        val validated = certificates.map { certificate ->
            val fingerprint = certificate.sha256()
            val pin = PINNED_SHA256[fingerprint]
                ?: throw TrustAnchorsUnavailable("Unexpected trust anchor fingerprint: $fingerprint")
            try {
                certificate.checkValidity(java.util.Date.from(now))
            } catch (expired: CertificateExpiredException) {
                throw TrustAnchorsUnavailable("Trust anchor expired: ${certificate.subjectX500Principal}", expired)
            } catch (notYetValid: CertificateNotYetValidException) {
                throw TrustAnchorsUnavailable("Trust anchor not yet valid: ${certificate.subjectX500Principal}", notYetValid)
            }
            certificate
        }
        val foundPins = validated.map { it.sha256() }.toSet()
        val missing = PINNED_SHA256.keys - foundPins
        if (missing.isNotEmpty()) {
            throw TrustAnchorsUnavailable("Missing required trust anchors: ${missing.joinToString()}")
        }
        return validated
    }

    private fun readCertificates(resources: Resources): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val stream = runCatching { resources.openRawResource(R.raw.iaca_roots) }
            .getOrElse { throw TrustAnchorsUnavailable("IACA trust bundle missing", it) }
        return stream.use { input ->
            factory.generateCertificates(input.buffered()).map { cert -> cert as X509Certificate }
        }
    }

    private fun InputStream.buffered(): InputStream = BufferedInputStream(this)

    private fun X509Certificate.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return digest.joinToString(separator = "") { byte -> String.format("%02x", byte) }
    }

    companion object {
        private val PINNED_SHA256 = mapOf(
            // AZ MVD publishes these anchors at azmvdnow.gov/certificates (hashes documented alongside downloads).
            "d92f739146fec8ad65da49a642167988c016cfe75605882b4025c3e6176ef55e" to "AZ MVD 2024 IACA", // 【AZMVD】
            "4800587c4f67896395bf883834121f98009b66b97c9315347e7c4971820ff4e1" to "AZ MVD Prod IACA 2021", // 【AZMVD】
            "b393b741300d08bfed6ce657e9c3de61df73dc838a00e48bbf0fe01c6caa27d1" to "AZ prod IACA certificate", // 【AZMVD】
        )
    }
}

