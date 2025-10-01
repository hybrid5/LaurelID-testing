package com.laurelid.auth

import com.laurelid.network.MapBackedTrustListApi
import com.laurelid.network.TrustListRepository
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider

class VerifierServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `verifier succeeds with trusted issuer`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS)
        )
        TestCredentialFixtures.withTempDir { dir ->
            val entries = mapOf(scenario.issuer to scenario.certificateBase64)
            val repository = TrustListRepository(MapBackedTrustListApi(entries), dir)
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertTrue(result.success)
            assertEquals(scenario.issuer, result.issuer)
            assertEquals(null, result.error)
        }
    }

    @Test
    fun `verifier fails when trust list unavailable`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS)
        )
        TestCredentialFixtures.withTempDir { dir ->
            val repository = object : TrustListRepository(MapBackedTrustListApi(emptyMap()), dir) {
                override suspend fun getOrRefresh(
                    nowMillis: Long,
                    maxAgeMillis: Long,
                    staleTtlMillis: Long,
                ): Snapshot {
                    return Snapshot(emptyMap(), stale = false)
                }
            }
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_TRUST_LIST_UNAVAILABLE, result.error)
        }
    }

    @Test
    fun `verifier uses stale trust snapshot`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS)
        )
        TestCredentialFixtures.withTempDir { dir ->
            val entries = mapOf(scenario.issuer to scenario.certificateBase64)
            val repository = object : TrustListRepository(MapBackedTrustListApi(entries), dir) {
                override suspend fun getOrRefresh(
                    nowMillis: Long,
                    maxAgeMillis: Long,
                    staleTtlMillis: Long,
                ): Snapshot {
                    return Snapshot(entries, stale = true)
                }
            }
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertTrue(result.success)
            assertEquals(scenario.issuer, result.issuer)
            assertEquals(null, result.error)
        }
    }

    @Test
    fun `verifier rejects tampered device signature`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS),
            tamperDeviceSignature = true
        )
        TestCredentialFixtures.withTempDir { dir ->
            val entries = mapOf(scenario.issuer to scenario.certificateBase64)
            val repository = TrustListRepository(MapBackedTrustListApi(entries), dir)
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_INVALID_DEVICE_SIGNATURE, result.error)
        }
    }

    @Test
    fun `verifier rejects issuer auth chain mismatch`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS),
            tamperIssuerChain = true
        )
        TestCredentialFixtures.withTempDir { dir ->
            val entries = mapOf(scenario.issuer to scenario.certificateBase64)
            val repository = TrustListRepository(MapBackedTrustListApi(entries), dir)
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_ISSUER_AUTH_CHAIN_MISMATCH, result.error)
        }
    }

    @Test
    fun `verifier rejects expired trust anchor`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS),
            trustAnchorValidUntil = clock.instant().minus(1, ChronoUnit.HOURS)
        )
        TestCredentialFixtures.withTempDir { dir ->
            val entries = mapOf(scenario.issuer to scenario.certificateBase64)
            val repository = TrustListRepository(MapBackedTrustListApi(entries), dir)
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_TRUST_ANCHOR_EXPIRED, result.error)
        }
    }
}
