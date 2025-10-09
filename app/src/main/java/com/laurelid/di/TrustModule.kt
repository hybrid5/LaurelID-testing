package com.laurelid.di

import android.content.Context
import com.laurelid.BuildConfig
import com.laurelid.trust.AssetTrustProvider
import com.laurelid.trust.TrustBootstrap
import com.laurelid.trust.TrustProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TrustModule {

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class PrimaryAnchors

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class DevAnchors

    @Provides
    @Singleton
    @PrimaryAnchors
    fun providePrimaryAnchors(@ApplicationContext context: Context): TrustProvider =
        AssetTrustProvider(context, AssetTrustProvider.DEFAULT_ASSET_PATH)

    @Provides
    @Singleton
    @DevAnchors
    fun provideDevAnchors(@ApplicationContext context: Context): TrustProvider? =
        if (BuildConfig.DEV_MODE) AssetTrustProvider(context, "trust/test_roots") else null

    @Provides
    @Singleton
    fun provideTrustBootstrap(
        @PrimaryAnchors primary: TrustProvider,
        clock: Clock,
        @DevAnchors dev: TrustProvider?,
    ): TrustBootstrap = TrustBootstrap(primary, clock, dev)
}
