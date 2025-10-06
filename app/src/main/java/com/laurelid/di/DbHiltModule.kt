// com/laurelid/di/DbHiltModule.kt
package com.laurelid.di

import android.content.Context
import com.laurelid.db.AppDb
import com.laurelid.db.DbModule
import com.laurelid.db.VerificationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DbHiltModule {
    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AppDb =
        DbModule.provideDatabase(ctx)

    @Provides
    fun provideVerificationDao(db: AppDb): VerificationDao =
        db.verificationDao()
}
