package com.laurelid.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustListApiFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun create(baseUrl: String): TrustListApi {
        return RetrofitModule.provideTrustListApi(context, baseUrl)
    }
}
