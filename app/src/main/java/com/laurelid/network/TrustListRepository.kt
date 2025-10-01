package com.laurelid.network

import android.content.Context
import com.laurelid.BuildConfig
import com.laurelid.util.Logger
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

open class TrustListRepository(
    api: TrustListApi,
    private val cacheDir: File,
    private val defaultMaxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val defaultStaleTtlMillis: Long = DEFAULT_STALE_TTL_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialBaseUrl: String? = null,
) {

    data class Snapshot(
        val entries: Map<String, String>,
        val stale: Boolean,
    )

    private data class CacheState(
        val entries: Map<String, String>,
        val fetchedAtMillis: Long,
    )

    private val mutex = Mutex()
    private val cacheFile: File = File(cacheDir, CACHE_FILE_NAME)

    @Volatile
    private var trustListApi: TrustListApi = api

    @Volatile
    private var baseUrl: String? = initialBaseUrl?.let { TrustListEndpointPolicy.requireEndpointAllowed(it) }

    @Volatile
    private var memoryCache: CacheState? = null

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    open suspend fun getOrRefresh(nowMillis: Long): Snapshot =
        getOrRefresh(nowMillis, defaultMaxAgeMillis, defaultStaleTtlMillis)

    open suspend fun getOrRefresh(
        nowMillis: Long,
        maxAgeMillis: Long,
        staleTtlMillis: Long = defaultStaleTtlMillis,
    ): Snapshot = mutex.withLock {
        val current = ensureCacheLoaded()
        val hasCache = current != null
        val isFresh = when {
            !hasCache -> false
            maxAgeMillis <= 0L -> false
            else -> nowMillis - current!!.fetchedAtMillis <= maxAgeMillis
        }

        if (hasCache && isFresh) {
            Logger.d(TAG, "Returning in-memory trust list (fresh)")
            return Snapshot(current!!.entries, stale = false)
        }

        return try {
            val remote = trustListApi.getTrustList()
            val sanitized = remote.toMap()
            val updatedState = CacheState(sanitized, nowMillis)
            memoryCache = updatedState
            persist(updatedState)
            Logger.i(TAG, "Trust list refreshed with ${sanitized.size} entries")
            Snapshot(sanitized, stale = false)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to refresh trust list, falling back to cache", throwable)
            if (current != null) {
                val age = nowMillis - current.fetchedAtMillis
                val staleThreshold = if (maxAgeMillis <= 0L) 0L else maxAgeMillis
                val effectiveTtl = staleTtlMillis.coerceAtLeast(0L)
                val allowStale = when {
                    effectiveTtl <= 0L && age > staleThreshold -> false
                    staleThreshold <= 0L -> age <= effectiveTtl || effectiveTtl == 0L
                    effectiveTtl == 0L -> age <= staleThreshold
                    else -> age <= staleThreshold + effectiveTtl
                }

                if (!allowStale) {
                    Logger.e(TAG, "Cached trust list expired beyond stale TTL; failing closed")
                    throw throwable
                }

                val stale = staleThreshold <= 0L || age > staleThreshold
                Logger.w(TAG, "Serving cached trust list (stale=$stale)")
                Snapshot(current.entries, stale = stale)
            } else {
                throw throwable
            }
        }
    }

    open fun cached(nowMillis: Long = System.currentTimeMillis()): Snapshot? {
        val state = memoryCache ?: return null
        val age = nowMillis - state.fetchedAtMillis
        val staleThreshold = if (defaultMaxAgeMillis <= 0L) 0L else defaultMaxAgeMillis
        val effectiveTtl = defaultStaleTtlMillis.coerceAtLeast(0L)

        val beyondTtl = when {
            staleThreshold <= 0L && effectiveTtl <= 0L -> age > 0L
            staleThreshold <= 0L -> age > effectiveTtl
            effectiveTtl <= 0L -> age > staleThreshold
            else -> age > staleThreshold + effectiveTtl
        }

        if (beyondTtl) {
            Logger.w(TAG, "Discarding cached trust list beyond stale TTL")
            memoryCache = null
            return null
        }

        val stale = staleThreshold <= 0L || age > staleThreshold
        return Snapshot(state.entries, stale)
    }

    open fun updateEndpoint(newApi: TrustListApi, newBaseUrl: String) {
        val sanitizedUrl = TrustListEndpointPolicy.requireEndpointAllowed(newBaseUrl)
        runBlocking {
            mutex.withLock {
                trustListApi = newApi
                baseUrl = sanitizedUrl
                memoryCache = null
            }
        }
    }

    open fun currentBaseUrl(): String? = baseUrl

    private suspend fun ensureCacheLoaded(): CacheState? {
        val existing = memoryCache
        if (existing != null) {
            return existing
        }

        val diskState = readFromDisk()
        if (diskState != null) {
            memoryCache = diskState
        }
        return diskState
    }

    private suspend fun persist(state: CacheState) {
        withContext(ioDispatcher) {
            try {
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val json = JSONObject().apply {
                    put(KEY_FETCHED_AT, state.fetchedAtMillis)
                    val entriesJson = JSONObject()
                    for ((issuer, certificate) in state.entries) {
                        entriesJson.put(issuer, certificate)
                    }
                    put(KEY_ENTRIES, entriesJson)
                }
                cacheFile.writeText(json.toString())
            } catch (throwable: Throwable) {
                Logger.e(TAG, "Unable to persist trust list cache", throwable)
            }
        }
    }

    private suspend fun readFromDisk(): CacheState? = withContext(ioDispatcher) {
        if (!cacheFile.exists()) {
            return@withContext null
        }

        try {
            val raw = cacheFile.readText()
            if (raw.isBlank()) {
                return@withContext null
            }

            val json = JSONObject(raw)
            val fetchedAt = json.optLong(KEY_FETCHED_AT, -1L)
            if (fetchedAt <= 0L) {
                return@withContext null
            }

            val entriesJson = json.optJSONObject(KEY_ENTRIES) ?: return@withContext null
            val iterator = entriesJson.keys()
            val entries = mutableMapOf<String, String>()
            while (iterator.hasNext()) {
                val issuer = iterator.next()
                val certificate = entriesJson.optString(issuer, null)
                if (certificate != null) {
                    entries[issuer] = certificate
                }
            }

            CacheState(entries.toMap(), fetchedAt)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to read trust list cache from disk", throwable)
            null
        }
    }

    companion object {
        private const val TAG = "TrustListRepo"
        private const val CACHE_DIRECTORY = "trust_list"
        private const val CACHE_FILE_NAME = "trust_list.json"
        private const val KEY_FETCHED_AT = "fetchedAt"
        private const val KEY_ENTRIES = "entries"
        private val DEFAULT_MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(12)
        private val DEFAULT_STALE_TTL_MILLIS = TimeUnit.DAYS.toMillis(3)

        fun create(
            context: Context,
            api: TrustListApi,
            defaultMaxAgeMillis: Long = BuildConfig.TRUST_LIST_CACHE_MAX_AGE_MILLIS,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            baseUrl: String = TrustListEndpointPolicy.defaultBaseUrl,
        ): TrustListRepository {
            val directory = File(context.filesDir, CACHE_DIRECTORY)
            val sanitizedBaseUrl = TrustListEndpointPolicy.requireEndpointAllowed(baseUrl)
            return TrustListRepository(
                api = api,
                cacheDir = directory,
                defaultMaxAgeMillis = defaultMaxAgeMillis,
                defaultStaleTtlMillis = BuildConfig.TRUST_LIST_CACHE_STALE_TTL_MILLIS,
                ioDispatcher = ioDispatcher,
                initialBaseUrl = sanitizedBaseUrl,
            )
        }
    }
}
