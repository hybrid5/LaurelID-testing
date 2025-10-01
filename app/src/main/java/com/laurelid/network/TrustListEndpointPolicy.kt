package com.laurelid.network

import com.laurelid.BuildConfig
import com.laurelid.config.AdminConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object TrustListEndpointPolicy {
    val defaultBaseUrl: String by lazy { normalize(BuildConfig.TRUST_LIST_BASE_URL) }
    val allowOverride: Boolean
        get() = BuildConfig.ALLOW_TRUST_LIST_OVERRIDE

    private val defaultHost: String by lazy { defaultBaseUrl.toHttpUrl().host }
    private val certificatePinRotations: List<PinRotation> by lazy {
        BuildConfig.TRUST_LIST_CERT_PINS
            .split(',')
            .mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) {
                    return@mapNotNull null
                }
                val parts = trimmed.split('@', limit = 2)
                val pin = parts[0].trim()
                if (pin.isEmpty()) {
                    return@mapNotNull null
                }
                val expiresAtMillis = parts.getOrNull(1)?.toLongOrNull()
                PinRotation(pin, expiresAtMillis)
            }
    }

    private val pinExpiryGraceMillis: Long
        get() = BuildConfig.TRUST_LIST_PIN_EXPIRY_GRACE_MILLIS

    fun resolveBaseUrl(config: AdminConfig?): String {
        val override = config?.apiEndpointOverride
        val normalizedOverride = normalizeOverrideOrNull(override, allowOverride)
        return normalizedOverride ?: defaultBaseUrl
    }

    fun requireEndpointAllowed(candidate: String): String {
        val normalized = normalize(candidate)
        if (!allowOverride && normalized.toHttpUrl().host != defaultHost) {
            throw IllegalArgumentException("Endpoint overrides are disabled in this build.")
        }
        return normalized
    }

    fun certificatePinsFor(baseUrl: String, nowMillis: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val normalized = normalize(baseUrl)
        val host = normalized.toHttpUrl().host
        return if (host == defaultHost) {
            val cutoff = if (pinExpiryGraceMillis > 0L) {
                nowMillis - pinExpiryGraceMillis
            } else {
                Long.MIN_VALUE
            }
            certificatePinRotations
                .filter { rotation ->
                    val expiresAt = rotation.expiresAtMillis
                    when {
                        expiresAt == null -> true
                        pinExpiryGraceMillis <= 0L -> nowMillis <= expiresAt
                        else -> cutoff <= expiresAt
                    }
                }
                .map { rotation -> host to rotation.pin }
        } else {
            emptyList()
        }
    }

    internal fun normalizeOverrideOrNull(candidate: String?, allow: Boolean = allowOverride): String? {
        if (!allow) {
            return null
        }
        val trimmed = candidate?.trim()
        if (trimmed.isNullOrEmpty()) {
            return null
        }
        return try {
            normalize(trimmed)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun normalize(candidate: String): String {
        val httpUrl = candidate.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid HTTPS URL: $candidate")
        if (httpUrl.scheme != "https") {
            throw IllegalArgumentException("Only HTTPS endpoints are supported.")
        }
        if (httpUrl.encodedUsername.isNotEmpty() || httpUrl.encodedPassword.isNotEmpty()) {
            throw IllegalArgumentException("Trust list endpoint must not embed credentials.")
        }
        return httpUrl.newBuilder().build().toString()
    }

    private data class PinRotation(
        val pin: String,
        val expiresAtMillis: Long?,
    )
}
