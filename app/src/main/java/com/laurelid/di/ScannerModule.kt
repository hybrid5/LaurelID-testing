package com.laurelid.di

import com.laurelid.scanner.CredentialParser
import com.laurelid.scanner.DefaultCredentialParser
import com.laurelid.scanner.VerificationExecutor
import com.laurelid.scanner.VerificationOrchestrator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface ScannerModule {
    @Binds
    fun bindCredentialParser(impl: DefaultCredentialParser): CredentialParser

    @Binds
    fun bindVerificationExecutor(impl: VerificationOrchestrator): VerificationExecutor
}
