package com.laurelid.di

import android.content.Context
import android.nfc.NfcAdapter
import com.laurelid.deviceengagement.NfcAdapterProvider as DeviceNfcAdapterProvider
import com.laurelid.auth.session.NfcAdapterProvider as SessionNfcAdapterProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideDeviceNfcAdapterProvider(
        @ApplicationContext context: Context,
    ): DeviceNfcAdapterProvider = DeviceNfcAdapterProvider { NfcAdapter.getDefaultAdapter(context) }

    @Provides
    @Singleton
    fun provideSessionNfcAdapterProvider(
        deviceProvider: DeviceNfcAdapterProvider,
    ): SessionNfcAdapterProvider = SessionNfcAdapterProvider { deviceProvider.get() }
}
