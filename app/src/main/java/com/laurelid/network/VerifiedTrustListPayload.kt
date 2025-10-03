package com.laurelid.network

internal data class VerifiedTrustListPayload(
    val response: TrustListResponse,
    val manifest: TrustListManifestVerifier.VerifiedManifest,
)
