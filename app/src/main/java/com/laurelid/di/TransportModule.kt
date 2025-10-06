package com.laurelid.di

import com.laurelid.auth.deviceengagement.DeviceEngagementTransportFactory
import com.laurelid.auth.deviceengagement.TransportFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds
    @Singleton
    abstract fun bindTransportFactory(
        impl: DeviceEngagementTransportFactory
    ): TransportFactory
}
