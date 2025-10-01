package com.laurelid.network

import com.laurelid.observability.InMemoryStructuredEventExporter
import com.laurelid.observability.StructuredEventLogger
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TrustListRepositoryTest {
    private lateinit var exporter: InMemoryStructuredEventExporter

    @BeforeTest
    fun setUp() {
        exporter = InMemoryStructuredEventExporter()
        StructuredEventLogger.registerExporter(exporter)
    }

    @AfterTest
    fun tearDown() {
        StructuredEventLogger.registerExporter(null)
    }

    @Test
    fun `fetches remote list and caches to disk`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(api, dir, defaultMaxAgeMillis = 10_000L)

            val snapshot = repository.getOrRefresh(nowMillis = 0L)

            assertEquals(mapOf("AZ" to "cert"), snapshot.entries)
            assertFalse(snapshot.stale)
            assertEquals(1, api.callCount)
            assertTrue(dir.resolve("trust_list.json").exists())
        }
    }

    @Test
    fun `returns cached list without hitting network when fresh`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(api, dir, defaultMaxAgeMillis = 10_000L)

            repository.getOrRefresh(nowMillis = 0L)
            val snapshot = repository.getOrRefresh(nowMillis = 5_000L, maxAgeMillis = 10_000L)

            assertEquals(mapOf("AZ" to "cert"), snapshot.entries)
            assertFalse(snapshot.stale)
            assertEquals(1, api.callCount)
        }
    }

    @Test
    fun `falls back to stale cache when refresh fails`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(
                api,
                dir,
                defaultMaxAgeMillis = 1_000L,
                defaultStaleTtlMillis = 5_000L,
                delayProvider = { _ -> },
            )

            repository.getOrRefresh(nowMillis = 0L)
            api.shouldFail = true

            val snapshot = repository.getOrRefresh(nowMillis = 5_000L, maxAgeMillis = 1_000L)

            assertEquals(mapOf("AZ" to "cert"), snapshot.entries)
            assertTrue(snapshot.stale)
            assertEquals(1 + TrustListRepository.DEFAULT_MAX_ATTEMPTS, api.callCount)
            val events = exporter.snapshot()
            assertTrue(events.any { it.event == "trust_list_stale_cache_served" })
        }
    }

    @Test
    fun `throws when cache older than stale ttl`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(
                api,
                dir,
                defaultMaxAgeMillis = 1_000L,
                defaultStaleTtlMillis = 2_000L,
                delayProvider = { _ -> },
            )

            repository.getOrRefresh(nowMillis = 0L)
            api.shouldFail = true

            assertFailsWith<IOException> {
                repository.getOrRefresh(nowMillis = 5_000L, maxAgeMillis = 1_000L)
            }
        }
    }

    @Test
    fun `cached snapshot evicted beyond stale ttl`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(
                api,
                dir,
                defaultMaxAgeMillis = 1_000L,
                defaultStaleTtlMillis = 2_000L,
            )

            repository.getOrRefresh(nowMillis = 0L)

            val present = repository.cached(nowMillis = 1_500L)
            assertNotNull(present)
            assertTrue(present.stale)

            val evicted = repository.cached(nowMillis = 4_000L)
            assertNull(evicted)
        }
    }

    @Test
    fun `max age zero allows stale cache within ttl`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(
                api,
                dir,
                defaultMaxAgeMillis = 1_000L,
                defaultStaleTtlMillis = 5_000L,
                delayProvider = { _ -> },
            )

            repository.getOrRefresh(nowMillis = 0L)
            api.shouldFail = true

            val snapshot = repository.getOrRefresh(
                nowMillis = 500L,
                maxAgeMillis = 0L,
                staleTtlMillis = 2_000L,
            )

            assertEquals(mapOf("AZ" to "cert"), snapshot.entries)
            assertTrue(snapshot.stale)
            assertEquals(1 + TrustListRepository.DEFAULT_MAX_ATTEMPTS, api.callCount)
        }
    }

    @Test
    fun `stale ttl zero forces failure once max age exceeded`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(
                api,
                dir,
                defaultMaxAgeMillis = 1_000L,
                defaultStaleTtlMillis = 0L,
                delayProvider = { _ -> },
            )

            repository.getOrRefresh(nowMillis = 0L)
            api.shouldFail = true

            assertFailsWith<IOException> {
                repository.getOrRefresh(
                    nowMillis = 2_000L,
                    maxAgeMillis = 1_000L,
                    staleTtlMillis = 0L,
                )
            }
        }
    }

    @Test
    fun `throws when refresh fails and no cache available`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            api.shouldFail = true
            val repository = TrustListRepository(
                api,
                dir,
                defaultMaxAgeMillis = 1_000L,
                delayProvider = { _ -> },
            )

            assertFailsWith<IOException> {
                repository.getOrRefresh(nowMillis = 0L)
            }
            assertEquals(TrustListRepository.DEFAULT_MAX_ATTEMPTS, api.callCount)
        }
    }

    @Test
    fun `loads cached data from disk on cold start`() = runBlocking {
        withTempDir { dir ->
            val initialApi = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(initialApi, dir, defaultMaxAgeMillis = 10_000L)
            repository.getOrRefresh(nowMillis = 0L)
            assertEquals(1, initialApi.callCount)

            val offlineApi = RecordingTrustListApi(emptyMap())
            offlineApi.shouldFail = true
            val coldRepository = TrustListRepository(offlineApi, dir, defaultMaxAgeMillis = 10_000L)

            val snapshot = coldRepository.getOrRefresh(nowMillis = 5_000L, maxAgeMillis = 10_000L)

            assertEquals(mapOf("AZ" to "cert"), snapshot.entries)
            assertFalse(snapshot.stale)
            assertEquals(0, offlineApi.callCount)
        }
    }

    @Test
    fun `retries transient failures before succeeding`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            api.transientFailuresBeforeSuccess = 2
            val repository = TrustListRepository(
                api,
                dir,
                defaultMaxAgeMillis = 10_000L,
                delayProvider = { _ -> },
            )

            val snapshot = repository.getOrRefresh(nowMillis = 0L)

            assertEquals(mapOf("AZ" to "cert"), snapshot.entries)
            assertFalse(snapshot.stale)
            assertEquals(3, api.callCount)
            val events = exporter.snapshot().filter { it.event == "trust_list_refresh" }
            assertEquals(listOf(false, false, true), events.mapNotNull { it.success })
        }
    }

    @Test
    fun `emits telemetry when cache too stale`() = runBlocking {
        withTempDir { dir ->
            val api = RecordingTrustListApi(mapOf("AZ" to "cert"))
            val repository = TrustListRepository(
                api,
                dir,
                defaultMaxAgeMillis = 1_000L,
                defaultStaleTtlMillis = 2_000L,
                delayProvider = { _ -> },
            )

            repository.getOrRefresh(nowMillis = 0L)
            api.shouldFail = true

            assertFailsWith<IOException> {
                repository.getOrRefresh(nowMillis = 5_000L, maxAgeMillis = 1_000L)
            }

            val events = exporter.snapshot().filter { it.event == "trust_list_cache_expired" }
            assertEquals(1, events.size)
            assertEquals(false, events.first().success)
        }
    }

    private fun <T> withTempDir(block: (java.io.File) -> T): T {
        val directory = createTempDirectory("trust-list-cache").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RecordingTrustListApi(
        private var payload: Map<String, String>,
    ) : TrustListApi {
        var shouldFail: Boolean = false
        var transientFailuresBeforeSuccess: Int = 0
        var callCount: Int = 0

        override suspend fun getTrustList(): Map<String, String> {
            callCount += 1
            if (transientFailuresBeforeSuccess > 0) {
                transientFailuresBeforeSuccess -= 1
                throw IOException("transient network failure")
            }
            if (shouldFail) {
                throw IOException("network unavailable")
            }
            return payload
        }
    }
}
