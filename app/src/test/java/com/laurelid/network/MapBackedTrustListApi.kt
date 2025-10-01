package com.laurelid.network

class MapBackedTrustListApi(
    private val payload: Map<String, String>,
) : TrustListApi {
    override suspend fun getTrustList(): Map<String, String> = payload
}
