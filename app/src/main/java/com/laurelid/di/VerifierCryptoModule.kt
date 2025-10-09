package com.laurelid.di

import android.content.Context
import android.nfc.NfcAdapter
import com.laurelid.auth.cose.CoseVerifier
import com.laurelid.auth.cose.DefaultCoseVerifier
import com.laurelid.auth.crypto.AndroidHpkeKeyProvider
import com.laurelid.auth.crypto.BouncyCastleHpkeEngine
import com.laurelid.auth.crypto.HpkeEngine
import com.laurelid.auth.crypto.HpkeKeyProvider
import com.laurelid.auth.trust.ResourceTrustStore
import com.laurelid.auth.trust.TrustStore
import com.laurelid.auth.session.NfcAdapterProvider
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
    abstract fun bindHpkeEngine(impl: BouncyCastleHpkeEngine): HpkeEngine

    @Binds
    @Singleton
    abstract fun bindCoseVerifier(impl: DefaultCoseVerifier): CoseVerifier

    @Binds
    @Singleton
    abstract fun bindTrustStore(impl: ResourceTrustStore): TrustStore

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
