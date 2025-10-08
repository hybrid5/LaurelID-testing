// com/laurelid/di/AuthModule.kt
package com.laurelid.di

import com.laurelid.auth.DeviceResponseParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {
    @Provides @Named("defaultDocType") fun defaultDocType(): String = "org.iso.18013.5.1"
    @Provides @Named("defaultIssuer") fun defaultIssuer(): String = "Unknown Issuer"

    @Provides @Singleton
    fun provideDeviceResponseParser(
        @Named("defaultDocType") docType: String,
        @Named("defaultIssuer") issuer: String
    ): DeviceResponseParser = DeviceResponseParser(docType, issuer)
}
