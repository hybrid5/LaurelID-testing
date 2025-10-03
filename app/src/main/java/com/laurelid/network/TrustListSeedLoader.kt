package com.laurelid.network

import com.laurelid.util.Logger
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

internal class TrustListSeedLoader(
    private val storage: TrustListCacheStorage,
    private val manifestVerifier: TrustListManifestVerifier,
    private val policy: Policy = Policy(),
) {

    data class Policy(
        val maxAgeMillis: Long = Long.MAX_VALUE,
        val staleTtlMillis: Long = Long.MAX_VALUE,
    ) {
        init {
            require(maxAgeMillis >= 0L) { "maxAgeMillis must be >= 0" }
            require(staleTtlMillis >= 0L) { "staleTtlMillis must be >= 0" }
        }
    }

    data class Seed(
        val payload: VerifiedTrustListPayload,
        val generatedAtMillis: Long,
        val staleTtlMillis: Long,
    )

    fun load(nowMillis: Long, currentBaseUrl: String?): Seed? {
        val raw = storage.read()?.takeIf { it.isNotBlank() } ?: return null
        val json = try {
            JSONObject(raw)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to parse trust seed JSON", throwable)
            return null
        }

        val declaredBaseUrl = json.optString(KEY_BASE_URL, null)
        if (!declaredBaseUrl.isNullOrBlank() &&
            !TrustListEndpointPolicy.endpointsMatch(declaredBaseUrl, currentBaseUrl)
        ) {
            Logger.w(TAG, "Ignoring seed trust list for mismatched endpoint")
            return null
        }

        val manifest = json.optString(KEY_MANIFEST, null)?.takeIf { it.isNotBlank() }
            ?: run {
                Logger.w(TAG, "Seed trust list missing manifest payload")
                return null
            }
        val signature = json.optString(KEY_SIGNATURE, null)?.takeIf { it.isNotBlank() }
            ?: run {
                Logger.w(TAG, "Seed trust list missing signature payload")
                return null
            }
        val chainArray = json.optJSONArray(KEY_CERTIFICATE_CHAIN) ?: JSONArray()
        val certificateChain = mutableListOf<String>()
        for (index in 0 until chainArray.length()) {
            val value = chainArray.optString(index, null)?.trim()
            if (!value.isNullOrEmpty()) {
                certificateChain.add(value)
            }
        }
        if (certificateChain.isEmpty()) {
            Logger.w(TAG, "Seed trust list missing certificate chain")
            return null
        }

        val response = TrustListResponse(
            manifest = manifest,
            signature = signature,
            certificateChain = certificateChain,
        )

        val verified = try {
            manifestVerifier.verify(response)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Seed trust list failed verification", throwable)
            return null
        }

        val generatedAtRaw = json.optLong(KEY_GENERATED_AT, 0L)
        val generatedAt = when {
            generatedAtRaw <= 0L -> 0L
            generatedAtRaw > nowMillis -> nowMillis
            else -> generatedAtRaw
        }
        val age = max(0L, nowMillis - generatedAt)
        if (policy.maxAgeMillis in 0 until Long.MAX_VALUE && age > policy.maxAgeMillis) {
            Logger.w(TAG, "Seed trust list exceeded max age; ignoring")
            return null
        }

        val staleTtl = json.optLong(KEY_STALE_TTL_MILLIS, policy.staleTtlMillis).let { rawValue ->
            when {
                rawValue <= 0L -> policy.staleTtlMillis
                policy.staleTtlMillis == Long.MAX_VALUE -> Long.MAX_VALUE
                else -> max(policy.staleTtlMillis, rawValue)
            }
        }

        val payload = VerifiedTrustListPayload(response = response, manifest = verified)
        return Seed(
            payload = payload,
            generatedAtMillis = generatedAt,
            staleTtlMillis = staleTtl,
        )
    }

    companion object {
        private const val TAG = "TrustSeedLoader"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_GENERATED_AT = "generatedAt"
        private const val KEY_MANIFEST = "manifest"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_CERTIFICATE_CHAIN = "certificateChain"
        private const val KEY_STALE_TTL_MILLIS = "staleTtlMillis"
    }
}
