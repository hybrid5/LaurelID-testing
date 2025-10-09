package com.laurelid.di

import com.laurelid.crypto.AndroidHpkeKeyProvider
import com.laurelid.crypto.BouncyCastleHpkeEngine
import com.laurelid.crypto.CoseVerifier
import com.laurelid.crypto.DefaultCoseVerifier
import com.laurelid.crypto.HpkeEngine
import com.laurelid.crypto.HpkeKeyProvider
import com.laurelid.auth.trust.ResourceTrustStore
import com.laurelid.auth.trust.TrustStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
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
}
