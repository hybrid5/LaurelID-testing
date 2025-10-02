package com.laurelid.network

import java.util.concurrent.TimeUnit

class MapBackedTrustListApi(
    private val payload: Map<String, String>,
    private val freshLifetimeMillis: Long = TimeUnit.HOURS.toMillis(12),
    private val staleLifetimeMillis: Long = TimeUnit.DAYS.toMillis(3),
    private val revokedSerials: Set<String> = emptySet(),
) : TrustListApi {
    override suspend fun getTrustList(): TrustListResponse =
        TrustListTestAuthority.signedResponse(
            entries = payload,
            freshLifetimeMillis = freshLifetimeMillis,
            staleLifetimeMillis = staleLifetimeMillis,
            revokedSerials = revokedSerials,
        )
}
