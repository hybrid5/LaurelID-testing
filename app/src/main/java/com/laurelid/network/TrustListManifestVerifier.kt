package com.laurelid.network

import com.laurelid.util.Logger
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Clock
import java.util.Base64
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class TrustListManifestVerifier(
    private val trustAnchors: Set<X509Certificate>,
    private val clock: Clock = Clock.systemUTC(),
) {

    data class VerifiedManifest(
        val entries: Map<String, String>,
        val revokedSerialNumbers: Set<String>,
        val freshLifetimeMillis: Long,
        val staleLifetimeMillis: Long,
        val signingCertificates: List<X509Certificate>,
        val manifestVersion: String?,
    )

    fun verify(response: TrustListResponse): VerifiedManifest {
        val manifestBytes = try {
            Base64.getDecoder().decode(response.manifest)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode trust list manifest payload", throwable)
            throw SecurityException("Malformed trust list manifest")
        }

        val signatureBytes = try {
            Base64.getDecoder().decode(response.signature)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode trust list manifest signature", throwable)
            throw SecurityException("Malformed trust list manifest signature")
        }

        val certificates = response.certificateChain.mapNotNull { certificateBase64 ->
            decodeCertificate(certificateBase64)
        }

        if (certificates.isEmpty()) {
            Logger.w(TAG, "Trust list response missing signing certificate chain")
            throw SecurityException("Manifest signing chain missing")
        }

        validateCertificates(certificates)
        validateCertificateChain(certificates)
        verifySignature(certificates.first(), manifestBytes, signatureBytes)

        val json = try {
            JSONObject(String(manifestBytes, Charsets.UTF_8))
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to parse trust list manifest JSON", throwable)
            throw SecurityException("Malformed trust list manifest JSON")
        }

        val entries = extractEntries(json)
        val revokedSerials = extractRevokedSerials(json)
        val freshLifetime = extractDuration(json, FRESH_LIFETIME_KEY)
        val staleLifetime = extractDuration(json, STALE_LIFETIME_KEY)
        val version = extractVersion(json)

        return VerifiedManifest(
            entries = entries,
            revokedSerialNumbers = revokedSerials,
            freshLifetimeMillis = freshLifetime,
            staleLifetimeMillis = staleLifetime,
            signingCertificates = certificates,
            manifestVersion = version,
        )
    }

    private fun extractEntries(json: JSONObject): Map<String, String> {
        val entriesJson = json.optJSONObject(ENTRIES_KEY)
            ?: throw SecurityException("Trust list manifest missing entries")
        val iterator = entriesJson.keys()
        val result = mutableMapOf<String, String>()
        while (iterator.hasNext()) {
            val issuer = iterator.next()
            val certificate = entriesJson.optString(issuer, null)
            if (certificate.isNullOrBlank()) {
                Logger.w(TAG, "Skipping empty trust list entry for issuer $issuer")
                continue
            }
            result[issuer] = certificate
        }
        if (result.isEmpty()) {
            throw SecurityException("Trust list manifest contained no usable entries")
        }
        return result
    }

    private fun extractRevokedSerials(json: JSONObject): Set<String> {
        val array = json.optJSONArray(REVOKED_SERIALS_KEY) ?: return emptySet()
        val normalized = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val raw = array.optString(index, null) ?: continue
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            normalized.add(trimmed.uppercase(Locale.US))
        }
        return normalized
    }

    private fun extractDuration(json: JSONObject, key: String): Long {
        val raw = json.optLong(key, Long.MIN_VALUE)
        return when {
            raw == Long.MIN_VALUE -> Long.MAX_VALUE
            raw <= 0L -> 0L
            else -> raw
        }
    }

    private fun extractVersion(json: JSONObject): String? {
        val raw = json.opt(VERSION_KEY) ?: return null
        return when (raw) {
            is Number -> raw.toLong().toString()
            is String -> raw.trim().takeIf { it.isNotEmpty() }
            else -> raw.toString().takeIf { it.isNotEmpty() }
        }
    }

    private fun validateCertificates(certificates: List<X509Certificate>) {
        val now = Date.from(clock.instant())
        for (certificate in certificates) {
            try {
                certificate.checkValidity(now)
            } catch (throwable: Throwable) {
                Logger.e(TAG, "Trust list signing certificate invalid", throwable)
                throw SecurityException("Trust list signing certificate invalid")
            }
        }
    }

    private fun validateCertificateChain(certificates: List<X509Certificate>) {
        if (trustAnchors.isEmpty()) {
            throw SecurityException("No trust anchors configured for trust list manifest")
        }

        val trustAnchorsSet = trustAnchors.map { TrustAnchor(it, null) }.toSet()
        val anchorEncodings = trustAnchors.map { it.encoded }.toSet()
        val intermediates = certificates.filterNot { anchorEncodings.contains(it.encoded) }

        val certFactory = CertificateFactory.getInstance("X.509")
        val certPath = certFactory.generateCertPath(intermediates)
        val parameters = PKIXParameters(trustAnchorsSet).apply {
            isRevocationEnabled = false
        }

        try {
            CertPathValidator.getInstance("PKIX").validate(certPath, parameters)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Trust list signing chain validation failed", throwable)
            throw SecurityException("Trust list signing chain validation failed")
        }
    }

    private fun verifySignature(
        signingCertificate: X509Certificate,
        manifestBytes: ByteArray,
        signatureBytes: ByteArray,
    ) {
        val signatureAlgorithm = signingCertificate.sigAlgName ?: DEFAULT_SIGNATURE_ALGORITHM
        try {
            val verifier = Signature.getInstance(signatureAlgorithm)
            verifier.initVerify(signingCertificate.publicKey)
            verifier.update(manifestBytes)
            if (!verifier.verify(signatureBytes)) {
                throw SecurityException("Trust list manifest signature invalid")
            }
        } catch (throwable: Throwable) {
            if (throwable is SecurityException) {
                Logger.w(TAG, throwable.message ?: "Trust list manifest signature invalid")
                throw throwable
            }
            Logger.e(TAG, "Error validating trust list manifest signature", throwable)
            throw SecurityException("Trust list manifest signature invalid")
        }
    }

    private fun decodeCertificate(base64: String): X509Certificate? {
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to decode manifest signing certificate", throwable)
            null
        }
    }

    companion object {
        private const val TAG = "TrustListManifestVerifier"
        private const val ENTRIES_KEY = "entries"
        private const val REVOKED_SERIALS_KEY = "revokedSerialNumbers"
        private const val FRESH_LIFETIME_KEY = "freshLifetimeMillis"
        private const val STALE_LIFETIME_KEY = "staleLifetimeMillis"
        private const val VERSION_KEY = "version"
        private const val DEFAULT_SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun fromBase64Anchors(raw: String?): Set<X509Certificate> {
            if (raw.isNullOrBlank()) {
                return emptySet()
            }
            val decoder = Base64.getDecoder()
            val anchors = mutableSetOf<X509Certificate>()
            val parts = raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            for (part in parts) {
                try {
                    val bytes = decoder.decode(part)
                    val factory = CertificateFactory.getInstance("X.509")
                    val certificate = factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
                    anchors.add(certificate)
                } catch (throwable: Throwable) {
                    Logger.e(TAG, "Unable to decode configured trust list anchor", throwable)
                }
            }
            return anchors
        }
    }
}
