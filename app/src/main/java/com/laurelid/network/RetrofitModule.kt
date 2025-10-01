package com.laurelid.network

import android.content.Context
import com.squareup.moshi.Moshi
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object RetrofitModule {
    private const val CACHE_SIZE_BYTES = 5L * 1024 * 1024 // 5 MiB
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val WRITE_TIMEOUT_SECONDS = 20L
    private const val CALL_TIMEOUT_SECONDS = 25L

    private val moshi: Moshi by lazy {
        Moshi.Builder().build()
    }

    fun provideTrustListApi(
        context: Context,
        baseUrl: String = TrustListEndpointPolicy.defaultBaseUrl,
    ): TrustListApi {
        val normalizedBaseUrl = TrustListEndpointPolicy.requireEndpointAllowed(baseUrl)
        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(provideOkHttpClient(context, normalizedBaseUrl))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
        return retrofit.create(TrustListApi::class.java)
    }

    private fun provideOkHttpClient(context: Context, baseUrl: String): OkHttpClient {
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, CACHE_SIZE_BYTES)
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()
        val builder = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(logging)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionSpecs(listOf(tlsSpec))

        val pins = TrustListEndpointPolicy.certificatePinsFor(baseUrl)
        if (pins.isNotEmpty()) {
            val pinnerBuilder = CertificatePinner.Builder()
            pins.forEach { (host, pin) ->
                pinnerBuilder.add(host, pin)
            }
            builder.certificatePinner(pinnerBuilder.build())
        }

        return builder.build()
    }
}
