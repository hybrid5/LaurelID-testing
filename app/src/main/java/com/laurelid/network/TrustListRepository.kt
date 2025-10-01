package com.laurelid.network

import android.content.Context
import com.laurelid.BuildConfig
import com.laurelid.observability.StructuredEventLogger
import com.laurelid.util.Logger
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val delayProvider: suspend (Long) -> Unit = { delay(it) },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {

    data class RetryPolicy(
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
        val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS,
        val backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER,
    ) {
        init {
            require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
            require(initialDelayMillis >= 0L) { "initialDelayMillis must be >= 0" }
            require(maxDelayMillis >= 0L) { "maxDelayMillis must be >= 0" }
            require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0" }
        }
    }

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
            val remote = fetchTrustListWithRetry()
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
                    StructuredEventLogger.log(
                        event = TRUST_LIST_CACHE_EXPIRED_EVENT,
                        timestampMs = System.currentTimeMillis(),
                        success = false,
                        reasonCode = REASON_STALE_EXPIRED,
                    )
                    throw throwable
                }

                val stale = staleThreshold <= 0L || age > staleThreshold
                Logger.w(TAG, "Serving cached trust list (stale=$stale)")
                StructuredEventLogger.log(
                    event = TRUST_LIST_STALE_CACHE_SERVED_EVENT,
                    timestampMs = System.currentTimeMillis(),
                    success = !stale,
                    reasonCode = if (stale) REASON_STALE_CACHE else REASON_CACHE_FRESH,
                )
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

    private fun computeNextDelay(previousDelayMillis: Long): Long {
        val base = if (previousDelayMillis <= 0L) 1L else previousDelayMillis
        val scaled = base * retryPolicy.backoffMultiplier
        return scaled.toLong().coerceAtLeast(1L)
    }

    private fun failureReasonCode(throwable: Throwable): String {
        val kotlinName = throwable::class.simpleName
        if (!kotlinName.isNullOrBlank()) {
            return kotlinName
        }
        val javaName = throwable.javaClass.simpleName
        if (javaName.isNotBlank()) {
            return javaName
        }
        return throwable.javaClass.name.substringAfterLast('.')
    }

    private suspend fun fetchTrustListWithRetry(): Map<String, String> {
        var attempt = 0
        var nextDelayMillis = retryPolicy.initialDelayMillis
        var lastError: Throwable? = null

        while (attempt < retryPolicy.maxAttempts) {
            val attemptStart = System.currentTimeMillis()
            try {
                val payload = trustListApi.getTrustList()
                val duration = System.currentTimeMillis() - attemptStart
                StructuredEventLogger.log(
                    event = TRUST_LIST_REFRESH_EVENT,
                    timestampMs = attemptStart,
                    durationMs = duration,
                    success = true,
                    reasonCode = if (attempt == 0) REASON_OK else REASON_RETRY_SUCCESS,
                )
                if (attempt > 0) {
                    Logger.i(TAG, "Trust list refresh succeeded after $attempt retries")
                }
                return payload
            } catch (throwable: Throwable) {
                lastError = throwable
                val duration = System.currentTimeMillis() - attemptStart
                StructuredEventLogger.log(
                    event = TRUST_LIST_REFRESH_EVENT,
                    timestampMs = attemptStart,
                    durationMs = duration,
                    success = false,
                    reasonCode = failureReasonCode(throwable),
                )
                Logger.w(TAG, "Trust list refresh attempt ${attempt + 1} failed", throwable)
                attempt += 1
                if (attempt >= retryPolicy.maxAttempts) {
                    break
                }

                val sleepMillis = nextDelayMillis.coerceAtMost(retryPolicy.maxDelayMillis)
                if (sleepMillis > 0L) {
                    delayProvider.invoke(sleepMillis)
                }
                nextDelayMillis = computeNextDelay(nextDelayMillis)
            }
        }

        throw lastError ?: IllegalStateException("Unable to refresh trust list")
    }

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
        internal const val DEFAULT_MAX_ATTEMPTS = 3
        private const val DEFAULT_INITIAL_DELAY_MILLIS = 500L
        private const val DEFAULT_MAX_DELAY_MILLIS = 5_000L
        private const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
        private const val TRUST_LIST_REFRESH_EVENT = "trust_list_refresh"
        private const val TRUST_LIST_STALE_CACHE_SERVED_EVENT = "trust_list_stale_cache_served"
        private const val TRUST_LIST_CACHE_EXPIRED_EVENT = "trust_list_cache_expired"
        private const val REASON_OK = "OK"
        private const val REASON_RETRY_SUCCESS = "RETRY_SUCCESS"
        private const val REASON_STALE_CACHE = "STALE_CACHE"
        private const val REASON_CACHE_FRESH = "CACHE_FRESH"
        private const val REASON_STALE_EXPIRED = "STALE_TTL_EXCEEDED"

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
