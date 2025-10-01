package com.laurelid.di

import com.laurelid.observability.IEventExporter
import com.laurelid.observability.NoopEventExporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelemetryModule {

    @Provides
    @Singleton
    fun provideEventExporter(): IEventExporter = NoopEventExporter
}
