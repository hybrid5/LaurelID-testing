package com.laurelid.di

import android.content.Context
import android.nfc.NfcAdapter
import com.laurelid.deviceengagement.NfcAdapterProvider
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
    fun provideNfcAdapterProvider(
        @ApplicationContext context: Context,
    ): NfcAdapterProvider = NfcAdapterProvider { NfcAdapter.getDefaultAdapter(context) }
}
