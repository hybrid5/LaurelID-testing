package com.laurelid.auth

import com.laurelid.network.MapBackedTrustListApi
import com.laurelid.network.TrustListRepository
import com.laurelid.network.TrustListTestAuthority
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider

class WalletVerifierTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val manifestVerifier = TrustListTestAuthority.manifestVerifier()

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `valid credential passes verification`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS)
        )
        TestCredentialFixtures.withTempDir { dir ->
            val repository = TrustListRepository(
                MapBackedTrustListApi(mapOf(scenario.issuer to scenario.certificateBase64)),
                dir,
                manifestVerifier = manifestVerifier,
                allowPlaintextCacheForTests = true,
            )
            val verifierService = VerifierService(repository, clock)
            val verifier = WalletVerifier(verifierService)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertTrue(result.success)
            assertTrue(result.ageOver21 == true)
            assertEquals(scenario.issuer, result.issuer)
            assertEquals(null, result.error)
        }
    }

    @Test
    fun `expired credential fails closed`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().minus(1, ChronoUnit.HOURS)
        )
        TestCredentialFixtures.withTempDir { dir ->
            val repository = TrustListRepository(
                MapBackedTrustListApi(mapOf(scenario.issuer to scenario.certificateBase64)),
                dir,
                manifestVerifier = manifestVerifier,
                allowPlaintextCacheForTests = true,
            )
            val verifierService = VerifierService(repository, clock)
            val verifier = WalletVerifier(verifierService)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_DOC_EXPIRED, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
        }
    }

    @Test
    fun `tampered device data is rejected`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS),
            tamperDeviceData = true
        )
        TestCredentialFixtures.withTempDir { dir ->
            val repository = TrustListRepository(
                MapBackedTrustListApi(mapOf(scenario.issuer to scenario.certificateBase64)),
                dir,
                manifestVerifier = manifestVerifier,
                allowPlaintextCacheForTests = true,
            )
            val verifierService = VerifierService(repository, clock)
            val verifier = WalletVerifier(verifierService)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_DEVICE_DATA_TAMPERED, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
        }
    }

    @Test
    fun `tampered device signature is rejected`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS),
            tamperDeviceSignature = true
        )
        TestCredentialFixtures.withTempDir { dir ->
            val repository = TrustListRepository(
                MapBackedTrustListApi(mapOf(scenario.issuer to scenario.certificateBase64)),
                dir,
                manifestVerifier = manifestVerifier,
                allowPlaintextCacheForTests = true,
            )
            val verifierService = VerifierService(repository, clock)
            val verifier = WalletVerifier(verifierService)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_INVALID_DEVICE_SIGNATURE, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
        }
    }

    @Test
    fun `issuer not in trust list is rejected`() = runBlocking {
        val scenario = TestCredentialFixtures.createScenario(
            clock = clock,
            validUntil = clock.instant().plus(1, ChronoUnit.DAYS)
        )
        TestCredentialFixtures.withTempDir { dir ->
            val repository = TrustListRepository(
                MapBackedTrustListApi(mapOf("OtherIssuer" to scenario.certificateBase64)),
                dir,
                manifestVerifier = manifestVerifier,
            allowPlaintextCacheForTests = true,
            )
            val verifierService = VerifierService(repository, clock)
            val verifier = WalletVerifier(verifierService)

            val result = verifier.verify(scenario.parsed, maxCacheAgeMillis = 0)

            assertFalse(result.success)
            assertEquals(VerifierService.ERROR_UNTRUSTED_ISSUER, result.error)
            assertNull(result.issuer)
            assertNull(result.subjectDid)
        }
    }
}
