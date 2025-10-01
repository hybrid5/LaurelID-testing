package com.laurelid.network

import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TrustListRepositoryTest {

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
            )

            repository.getOrRefresh(nowMillis = 0L)
            api.shouldFail = true

            val snapshot = repository.getOrRefresh(nowMillis = 5_000L, maxAgeMillis = 1_000L)

            assertEquals(mapOf("AZ" to "cert"), snapshot.entries)
            assertTrue(snapshot.stale)
            assertEquals(2, api.callCount)
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
            assertEquals(2, api.callCount)
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
            val repository = TrustListRepository(api, dir, defaultMaxAgeMillis = 1_000L)

            assertFailsWith<IOException> {
                repository.getOrRefresh(nowMillis = 0L)
            }
            assertEquals(1, api.callCount)
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
        var callCount: Int = 0

        override suspend fun getTrustList(): Map<String, String> {
            callCount += 1
            if (shouldFail) {
                throw IOException("network unavailable")
            }
            return payload
        }
    }
}
