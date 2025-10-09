package com.laurelid.di

import android.content.Context
import android.nfc.NfcAdapter
import com.laurelid.verifier.crypto.CoseVerifier
import com.laurelid.verifier.crypto.CoseVerifierImpl
import com.laurelid.verifier.crypto.HpkeEngine
import com.laurelid.verifier.crypto.HpkeEngineImpl
import com.laurelid.verifier.crypto.HpkeKeyProvider
import com.laurelid.verifier.crypto.AndroidHpkeKeyProvider
import com.laurelid.verifier.trust.TrustStore
import com.laurelid.verifier.trust.TrustStoreImpl
import com.laurelid.verifier.transport.NfcAdapterProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VerifierCryptoModule {

    @Binds
    @Singleton
    abstract fun bindHpkeEngine(impl: HpkeEngineImpl): HpkeEngine

    @Binds
    @Singleton
    abstract fun bindCoseVerifier(impl: CoseVerifierImpl): CoseVerifier

    @Binds
    @Singleton
    abstract fun bindTrustStore(impl: TrustStoreImpl): TrustStore

    @Binds
    @Singleton
    abstract fun bindHpkeKeyProvider(impl: AndroidHpkeKeyProvider): HpkeKeyProvider

    companion object {
        @Provides
        @Singleton
        fun provideNfcAdapterProvider(
            @ApplicationContext context: Context,
        ): NfcAdapterProvider = NfcAdapterProvider { NfcAdapter.getDefaultAdapter(context) }
    }
}
