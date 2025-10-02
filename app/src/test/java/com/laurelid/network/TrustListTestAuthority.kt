package com.laurelid.network

import com.laurelid.BuildConfig
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

object TrustListTestAuthority {
    private val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")

    val rootCertificate: X509Certificate by lazy {
        val encoded = Base64.getDecoder().decode(BuildConfig.TRUST_LIST_MANIFEST_ROOT_CERT)
        certificateFactory.generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
    }

    private val signingKey: PrivateKey by lazy {
        val normalized = TEST_ROOT_PRIVATE_KEY_PEM
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .trim()
        val decoded = Base64.getDecoder().decode(normalized)
        val keySpec = PKCS8EncodedKeySpec(decoded)
        KeyFactory.getInstance("EC").generatePrivate(keySpec)
    }

    fun manifestVerifier(): TrustListManifestVerifier = TrustListManifestVerifier(setOf(rootCertificate))

    fun signedResponse(
        entries: Map<String, String>,
        freshLifetimeMillis: Long = TimeUnit.HOURS.toMillis(12),
        staleLifetimeMillis: Long = TimeUnit.DAYS.toMillis(3),
        revokedSerials: Set<String> = emptySet(),
        version: Long = 1L,
    ): TrustListResponse {
        val manifestJson = JSONObject().apply {
            put("entries", JSONObject(entries))
            put("freshLifetimeMillis", freshLifetimeMillis)
            put("staleLifetimeMillis", staleLifetimeMillis)
            val revokedArray = JSONArray()
            revokedSerials.forEach { serial ->
                val normalized = serial.trim().uppercase(Locale.US)
                if (normalized.isNotEmpty()) {
                    revokedArray.put(normalized)
                }
            }
            put("revokedSerialNumbers", revokedArray)
            put("version", version)
        }

        val manifestBytes = manifestJson.toString().toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(signingKey)
            update(manifestBytes)
        }.sign()

        return TrustListResponse(
            manifest = Base64.getEncoder().encodeToString(manifestBytes),
            signature = Base64.getEncoder().encodeToString(signature),
            certificateChain = listOf(Base64.getEncoder().encodeToString(rootCertificate.encoded)),
        )
    }

    private const val TEST_ROOT_PRIVATE_KEY_PEM = """
        -----BEGIN PRIVATE KEY-----
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgvJjgMketJeomn2V2
        cduYloQMv3Zv1nblEdJcY8sv5g+hRANCAATKuPLAbgJB9V+7sK0qdZGZNBRH05f9
        o2hZXqQkOIuMM7PLBPxD7QreQAt7+Jri7ZUE6OMpgxLaNypyzlMlnCuy
        -----END PRIVATE KEY-----
    """
}
