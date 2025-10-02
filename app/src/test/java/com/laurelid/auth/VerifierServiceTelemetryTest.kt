package com.laurelid.auth

import com.laurelid.network.TrustListApi
import com.laurelid.network.TrustListRepository
import com.laurelid.network.TrustListResponse
import com.laurelid.network.TrustListTestAuthority
import com.laurelid.observability.InMemoryStructuredEventExporter
import com.laurelid.observability.StructuredEventLogger
import java.io.File
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class VerifierServiceTelemetryTest {
    private lateinit var cacheDir: File
    private lateinit var trustListRepository: TrustListRepository
    private val exporter = InMemoryStructuredEventExporter()

    @BeforeTest
    fun setUp() {
        cacheDir = createTempDir(prefix = "trust-cache")
        val api = object : TrustListApi {
            override suspend fun getTrustList(): TrustListResponse =
                TrustListTestAuthority.signedResponse(emptyMap())
        }
        trustListRepository = TrustListRepository(
            api = api,
            cacheDir = cacheDir,
            defaultMaxAgeMillis = 0L,
            defaultStaleTtlMillis = 0L,
            ioDispatcher = Dispatchers.Unconfined,
            manifestVerifier = TrustListTestAuthority.manifestVerifier(),
        )
        StructuredEventLogger.registerExporter(exporter)
        exporter.clear()
    }

    @AfterTest
    fun tearDown() {
        StructuredEventLogger.registerExporter(null)
        cacheDir.deleteRecursively()
    }

    @Test
    fun `logs telemetry when verification fails early`() = runBlocking {
        val service = VerifierService(trustListRepository, Clock.systemUTC())
        val parsed = ParsedMdoc(
            subjectDid = "did:example:123",
            docType = "org.iso.18013.5.1.mDL",
            issuer = "Issuer",
            ageOver21 = true,
        )

        val result = service.verify(parsed, maxCacheAgeMillis = 0L)

        assertFalse(result.success)
        val events = exporter.snapshot()
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("verification_completed", event.event)
        assertEquals(false, event.success)
        assertEquals(VerifierService.ERROR_NOT_IMPLEMENTED, event.reasonCode)
        assertNull(event.trustStale)
        assertNotNull(event.scanDurationMs)
        assertTrue((event.scanDurationMs ?: 0L) >= 0L)
    }
}
