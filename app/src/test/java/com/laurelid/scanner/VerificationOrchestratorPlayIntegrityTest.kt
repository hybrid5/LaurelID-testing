package com.laurelid.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laurelid.auth.ParsedMdoc
import com.laurelid.auth.VerifierService
import com.laurelid.auth.WalletVerifier
import com.laurelid.config.AdminConfig
import com.laurelid.data.VerificationResult
import com.laurelid.db.VerificationDao
import com.laurelid.db.VerificationEntity
import com.laurelid.integrity.PlayIntegrityGate
import com.laurelid.integrity.PlayIntegrityVerdict
import com.laurelid.integrity.PlayIntegrityVerdictProvider
import com.laurelid.network.TrustListApi
import com.laurelid.network.TrustListManifestVerifier
import com.laurelid.network.TrustListRepository
import com.laurelid.network.TrustListResponse
import com.laurelid.network.TrustListCacheStorage
import com.laurelid.pos.TransactionManager
import com.laurelid.util.LogManager
import java.io.File
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class VerificationOrchestratorPlayIntegrityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var verifierService: FakeVerifierService
    private lateinit var orchestrator: VerificationOrchestrator
    private lateinit var verificationDao: FakeVerificationDao
    private lateinit var logManager: FakeLogManager

    private val parsedMdoc = ParsedMdoc(
        subjectDid = "did:example:123",
        docType = "org.iso.18013.5.1.mDL",
        issuer = "AZ-MVD",
        ageOver21 = true,
    )

    @BeforeTest
    fun setUp() {
        PlayIntegrityGate.resetForTesting()
        verificationDao = FakeVerificationDao()
        verifierService = FakeVerifierService()
        logManager = FakeLogManager(context)
        orchestrator = VerificationOrchestrator(
            context = context,
            walletVerifier = WalletVerifier(verifierService),
            logManager = logManager,
            transactionManager = TransactionManager(),
            dispatcher = dispatcher,
            verificationDao = verificationDao,
        )
    }

    @AfterTest
    fun tearDown() {
        PlayIntegrityGate.resetForTesting()
    }

    @Test
    fun verifyBlocksWhenIntegrityFails() = runTest(dispatcher) {
        PlayIntegrityGate.setHelperFactoryForTesting { FakeProvider(PlayIntegrityVerdict.FAILED_DEVICE_INTEGRITY) }

        val result = orchestrator.verify(parsedMdoc, AdminConfig(), demoPayloadUsed = false)

        assertFalse(result.success)
        assertEquals(VerifierService.ERROR_DEVICE_INTEGRITY, result.error)
        assertEquals(0, verifierService.invocationCount.get())
        assertEquals(1, verificationDao.inserted.size)
        assertFalse(verificationDao.inserted.single().success)
        assertEquals(result, logManager.loggedResults.single())
    }

    @Test
    fun verifyDelegatesWhenIntegrityPasses() = runTest(dispatcher) {
        PlayIntegrityGate.setHelperFactoryForTesting { FakeProvider(PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY) }
        val expected = VerificationResult(
            success = true,
            ageOver21 = true,
            issuer = "AZ-MVD",
            subjectDid = "did:example:123",
            docType = "org.iso.18013.5.1.mDL",
            error = null,
            trustStale = false,
        )
        verifierService.nextResult = expected

        val result = orchestrator.verify(parsedMdoc, AdminConfig(), demoPayloadUsed = false)

        assertTrue(result.success)
        assertEquals(expected, result)
        assertEquals(1, verifierService.invocationCount.get())
    }

    private class FakeProvider(
        private val verdict: PlayIntegrityVerdict,
    ) : PlayIntegrityVerdictProvider {
        override suspend fun fetchVerdict(): PlayIntegrityVerdict = verdict
    }

    private class FakeVerificationDao : VerificationDao {
        val inserted = mutableListOf<VerificationEntity>()

        override suspend fun insert(entity: VerificationEntity) {
            inserted += entity
        }

        override suspend fun latest(limit: Int): List<VerificationEntity> {
            return inserted.takeLast(limit).reversed()
        }

        override suspend fun mostRecent(): VerificationEntity? = inserted.lastOrNull()
    }

    private class FakeVerifierService : VerifierService(
        trustListRepository = FakeTrustListRepository(),
        clock = Clock.systemUTC(),
    ) {
        val invocationCount = AtomicInteger(0)
        var nextResult: VerificationResult = VerificationResult(
            success = false,
            ageOver21 = null,
            issuer = null,
            subjectDid = null,
            docType = null,
            error = VerifierService.ERROR_CLIENT_EXCEPTION,
            trustStale = null,
        )

        override suspend fun verify(parsed: ParsedMdoc, maxCacheAgeMillis: Long): VerificationResult {
            invocationCount.incrementAndGet()
            return nextResult
        }
    }

    private class FakeTrustListRepository : TrustListRepository(
        api = object : TrustListApi {
            override suspend fun getTrustList(): TrustListResponse {
                throw UnsupportedOperationException("Unused in tests")
            }
        },
        cacheDir = File(System.getProperty("java.io.tmpdir"), "trust-list-cache"),
        defaultMaxAgeMillis = 0L,
        defaultStaleTtlMillis = 0L,
        ioDispatcher = Dispatchers.IO,
        initialBaseUrl = null,
        manifestVerifier = object : TrustListManifestVerifier(emptySet()) {
            override fun verify(response: TrustListResponse): VerifiedManifest {
                throw UnsupportedOperationException("Unused in tests")
            }
        },
        cacheStorage = object : TrustListCacheStorage {
            override fun read(): String? = null
            override fun write(contents: String) {}
            override fun delete() {}
        },
        seedLoader = null,
    ) {
        override suspend fun getOrRefresh(nowMillis: Long): Snapshot {
            throw UnsupportedOperationException("Unused in tests")
        }

        override suspend fun getOrRefresh(nowMillis: Long, maxAgeMillis: Long, staleTtlMillis: Long): Snapshot {
            throw UnsupportedOperationException("Unused in tests")
        }
    }

    private class FakeLogManager(
        context: Context,
    ) : LogManager(context, Clock.systemUTC()) {
        val loggedResults = mutableListOf<VerificationResult>()

        override fun appendVerification(result: VerificationResult, config: AdminConfig, demoModeUsed: Boolean) {
            loggedResults += result
        }
    }
}
