package com.laurelid.di

import android.content.Context
import com.laurelid.observability.FileStructuredEventExporter
import com.laurelid.observability.IEventExporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelemetryModule {

    @Provides
    @Singleton
    fun provideEventExporter(
        @ApplicationContext context: Context,
    ): IEventExporter = FileStructuredEventExporter(context)
}
