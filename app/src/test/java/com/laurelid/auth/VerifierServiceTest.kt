package com.laurelid.auth

import com.laurelid.network.MapBackedTrustListApi
import com.laurelid.network.TrustListRepository
import com.laurelid.network.TrustListTestAuthority
import java.io.ByteArrayInputStream
import java.security.Security
import java.security.cert.CertificateFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider

class VerifierServiceTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val manifestVerifier = TrustListTestAuthority.manifestVerifier()

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
            val repository = TrustListRepository(
                MapBackedTrustListApi(entries),
                dir,
                manifestVerifier = manifestVerifier,
            )
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
            val repository = object : TrustListRepository(
                MapBackedTrustListApi(emptyMap()),
                dir,
                manifestVerifier = manifestVerifier,
            ) {
                override suspend fun getOrRefresh(
                    nowMillis: Long,
                    maxAgeMillis: Long,
                    staleTtlMillis: Long,
                ): Snapshot {
                    return Snapshot(emptyMap(), emptySet(), stale = false)
                }
            }
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_TRUST_LIST_UNAVAILABLE, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
        }
    }

    @Test
    fun `verifier fails closed when trust list refresh throws`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS)
        )
        TestCredentialFixtures.withTempDir { dir ->
            val repository = object : TrustListRepository(
                MapBackedTrustListApi(emptyMap()),
                dir,
                manifestVerifier = manifestVerifier,
            ) {
                override suspend fun getOrRefresh(
                    nowMillis: Long,
                    maxAgeMillis: Long,
                    staleTtlMillis: Long,
                ): Snapshot {
                    throw java.io.IOException("network down")
                }
            }
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_TRUST_LIST_UNAVAILABLE, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
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
            val repository = object : TrustListRepository(
                MapBackedTrustListApi(entries),
                dir,
                manifestVerifier = manifestVerifier,
            ) {
                override suspend fun getOrRefresh(
                    nowMillis: Long,
                    maxAgeMillis: Long,
                    staleTtlMillis: Long,
                ): Snapshot {
                    return Snapshot(entries, emptySet(), stale = true)
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
            val repository = TrustListRepository(
                MapBackedTrustListApi(entries),
                dir,
                manifestVerifier = manifestVerifier,
            )
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_INVALID_DEVICE_SIGNATURE, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
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
            val repository = TrustListRepository(
                MapBackedTrustListApi(entries),
                dir,
                manifestVerifier = manifestVerifier,
            )
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_ISSUER_AUTH_CHAIN_MISMATCH, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
        }
    }

    @Test
    fun `verifier rejects revoked trust anchor`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS),
        )
        TestCredentialFixtures.withTempDir { dir ->
            val entries = mapOf(scenario.issuer to scenario.certificateBase64)
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val decoded = Base64.getDecoder().decode(scenario.certificateBase64)
            val certificate = certificateFactory.generateCertificate(ByteArrayInputStream(decoded)) as java.security.cert.X509Certificate
            val revoked = setOf(certificate.serialNumber.toString(16).uppercase())
            val repository = TrustListRepository(
                MapBackedTrustListApi(entries, revokedSerials = revoked),
                dir,
                manifestVerifier = manifestVerifier,
            )
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_CERTIFICATE_REVOKED, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
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
            val repository = TrustListRepository(
                MapBackedTrustListApi(entries),
                dir,
                manifestVerifier = manifestVerifier,
            )
            val service = VerifierService(repository, clock)

            val result = service.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_TRUST_ANCHOR_EXPIRED, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
        }
    }

    @Test
    fun `sanitizeReasonCode normalizes and falls back`() {
        assertNull(VerifierService.sanitizeReasonCode(null))
        assertEquals(
            VerifierService.ERROR_INVALID_SIGNATURE,
            VerifierService.sanitizeReasonCode("invalid_signature"),
        )
        assertEquals(
            VerifierService.ERROR_CLIENT_EXCEPTION,
            VerifierService.sanitizeReasonCode("Invalid signature from did:example:alice"),
        )
    }
}
