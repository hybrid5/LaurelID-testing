package com.laurelid.network

import retrofit2.http.GET

data class TrustListResponse(
    val manifest: String,
    val signature: String,
    val certificateChain: List<String>,
)

interface TrustListApi {
    @GET("trust-list.json")
    suspend fun getTrustList(): TrustListResponse
}
