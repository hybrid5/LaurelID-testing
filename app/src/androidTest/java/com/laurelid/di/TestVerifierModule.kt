package com.laurelid.di

import android.content.Context
import com.laurelid.auth.ParsedMdoc
import com.laurelid.auth.VerifierService
import com.laurelid.data.VerificationResult
import com.laurelid.config.AdminConfig
import com.laurelid.network.TrustListApi
import com.laurelid.network.TrustListRepository
import com.laurelid.network.TrustListEndpointPolicy
import com.laurelid.util.LogManager
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.TestInstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import java.time.Instant
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [VerifierModule::class]
)
object TestVerifierModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    @Provides
    @Named("trustListBaseUrl")
    fun provideTrustListBaseUrl(): String = TrustListEndpointPolicy.defaultBaseUrl

    @Provides
    @Singleton
    fun provideTrustListRepository(
        @ApplicationContext context: Context,
    ): TrustListRepository = FakeTrustListRepository(context)

    @Provides
    @Singleton
    fun provideVerifierService(
        repository: TrustListRepository,
        clock: Clock,
    ): VerifierService = FakeVerifierService(repository, clock)

    @Provides
    @Singleton
    fun provideLogManager(
        @ApplicationContext context: Context,
        clock: Clock,
    ): LogManager = FakeLogManager(context, clock)
}

private class FakeTrustListRepository(
    context: Context,
    private val entries: MutableMap<String, String> = mutableMapOf(),
) : TrustListRepository(
    api = object : TrustListApi {
        override suspend fun getTrustList(): Map<String, String> = entries
    },
    cacheDir = File(context.cacheDir, "fake_trust_list"),
    defaultMaxAgeMillis = Long.MAX_VALUE,
    defaultStaleTtlMillis = Long.MAX_VALUE,
    ioDispatcher = Dispatchers.IO,
    initialBaseUrl = TrustListEndpointPolicy.defaultBaseUrl,
) {
    private var snapshot: Snapshot = Snapshot(emptyMap(), stale = false)
    private var baseUrl: String = TrustListEndpointPolicy.defaultBaseUrl

    fun setEntries(newEntries: Map<String, String>, stale: Boolean = false) {
        entries.clear()
        entries.putAll(newEntries)
        snapshot = Snapshot(entries.toMap(), stale)
    }

    override suspend fun getOrRefresh(nowMillis: Long): Snapshot = snapshot

    override suspend fun getOrRefresh(
        nowMillis: Long,
        maxAgeMillis: Long,
        staleTtlMillis: Long,
    ): Snapshot = snapshot

    override fun cached(nowMillis: Long): Snapshot? = snapshot

    override fun updateEndpoint(newApi: TrustListApi, newBaseUrl: String) {
        baseUrl = newBaseUrl
    }

    override fun currentBaseUrl(): String? = baseUrl
}

private class FakeVerifierService(
    trustListRepository: TrustListRepository,
    clock: Clock,
) : VerifierService(trustListRepository, clock) {
    override suspend fun verify(parsed: ParsedMdoc, maxCacheAgeMillis: Long): VerificationResult {
        return VerificationResult(
            success = true,
            ageOver21 = true,
            issuer = parsed.issuer,
            subjectDid = parsed.subjectDid,
            docType = parsed.docType,
            error = null,
        )
    }
}

private class FakeLogManager(
    context: Context,
    clock: Clock,
) : LogManager(context, clock) {
    override fun purgeLegacyLogs() {}
    override fun purgeOldLogs() {}
    override fun appendVerification(result: VerificationResult, config: com.laurelid.config.AdminConfig, demoModeUsed: Boolean) {}
}
