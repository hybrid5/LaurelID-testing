// com/laurelid/di/CoroutinesBridgeModule.kt
package com.laurelid.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesBridgeModule {
    @Provides
    fun provideUnqualifiedDispatcher(
        @IoDispatcher io: CoroutineDispatcher
    ): CoroutineDispatcher = io
}
