package com.laurelid.di

import android.content.Context
import com.laurelid.auth.VerifierService
import com.laurelid.network.TrustListApi
import com.laurelid.network.TrustListApiFactory
import com.laurelid.network.TrustListEndpointPolicy
import com.laurelid.network.TrustListRepository
import com.laurelid.util.LogManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VerifierModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Named("trustListBaseUrl")
    fun provideTrustListBaseUrl(): String = TrustListEndpointPolicy.defaultBaseUrl

    @Provides
    @Singleton
    fun provideTrustListApi(
        factory: TrustListApiFactory,
        @Named("trustListBaseUrl") baseUrl: String,
    ): TrustListApi = factory.create(baseUrl)

    @Provides
    @Singleton
    fun provideTrustListRepository(
        trustListApi: TrustListApi,
        @ApplicationContext context: Context,
        @Named("trustListBaseUrl") baseUrl: String,
    ): TrustListRepository = TrustListRepository.create(context, trustListApi, baseUrl = baseUrl)

    @Provides
    @Singleton
    fun provideVerifierService(
        trustListRepository: TrustListRepository,
        clock: Clock,
    ): VerifierService = VerifierService(trustListRepository, clock)

    @Provides
    @Singleton
    fun provideLogManager(
        @ApplicationContext context: Context,
        clock: Clock,
    ): LogManager = LogManager(context, clock)
}
