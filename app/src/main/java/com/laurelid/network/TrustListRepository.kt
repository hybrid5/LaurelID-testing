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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

open class TrustListRepository(
    api: TrustListApi,
    private val cacheDir: File,
    private val defaultMaxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val defaultStaleTtlMillis: Long = DEFAULT_STALE_TTL_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialBaseUrl: String? = null,
    private val delayProvider: suspend (Long) -> Unit = { delay(it) },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val manifestVerifier: TrustListManifestVerifier,
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
        val revokedSerialNumbers: Set<String>,
        val stale: Boolean,
    )

    private data class CacheState(
        val entries: Map<String, String>,
        val fetchedAtMillis: Long,
        val freshLifetimeMillis: Long,
        val staleLifetimeMillis: Long,
        val revokedSerialNumbers: Set<String>,
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
        val age = current?.let { nowMillis - it.fetchedAtMillis } ?: 0L
        val freshDuration = current?.let { combineDuration(maxAgeMillis, it.freshLifetimeMillis) }
        val isFresh = current != null && freshDuration != null &&
            (freshDuration == Long.MAX_VALUE || age <= freshDuration)

        if (isFresh && current != null) {
            Logger.d(TAG, "Returning in-memory trust list (fresh)")
            return Snapshot(current.entries, current.revokedSerialNumbers, stale = false)
        }

        return try {
            val remote = fetchTrustListWithRetry()
            val sanitized = remote.entries
            val updatedState = CacheState(
                entries = sanitized,
                fetchedAtMillis = nowMillis,
                freshLifetimeMillis = remote.freshLifetimeMillis,
                staleLifetimeMillis = remote.staleLifetimeMillis,
                revokedSerialNumbers = remote.revokedSerialNumbers,
            )
            memoryCache = updatedState
            persist(updatedState)
            Logger.i(TAG, "Trust list refreshed with ${sanitized.size} entries")
            Snapshot(sanitized, remote.revokedSerialNumbers, stale = false)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to refresh trust list, falling back to cache", throwable)
            if (current != null) {
                val fallbackFresh = freshDuration ?: combineDuration(maxAgeMillis, current.freshLifetimeMillis)
                val staleDuration = combineDuration(staleTtlMillis, current.staleLifetimeMillis)
                val allowStale = when {
                    staleDuration == 0L && fallbackFresh == 0L -> true
                    staleDuration == 0L -> false
                    staleDuration == Long.MAX_VALUE -> true
                    fallbackFresh == 0L -> age <= staleDuration
                    fallbackFresh == Long.MAX_VALUE -> age <= staleDuration
                    else -> age <= fallbackFresh + staleDuration
                }

                if (!allowStale) {
                    Logger.e(TAG, "Cached trust list expired beyond stale TTL; failing closed")
                    StructuredEventLogger.log(
                        event = TRUST_LIST_CACHE_EXPIRED_EVENT,
                        timestampMs = System.currentTimeMillis(),
                        success = false,
                        reasonCode = REASON_STALE_EXPIRED,
                        trustStale = true,
                    )
                    throw throwable
                }

                val stale = when {
                    fallbackFresh == Long.MAX_VALUE -> false
                    fallbackFresh == 0L -> true
                    else -> age > fallbackFresh
                }
                Logger.w(TAG, "Serving cached trust list (stale=$stale)")
                StructuredEventLogger.log(
                    event = TRUST_LIST_STALE_CACHE_SERVED_EVENT,
                    timestampMs = System.currentTimeMillis(),
                    success = !stale,
                    reasonCode = if (stale) REASON_STALE_CACHE else REASON_CACHE_FRESH,
                    trustStale = stale,
                )
                Snapshot(current.entries, current.revokedSerialNumbers, stale = stale)
            } else {
                throw throwable
            }
        }
    }

    open fun cached(nowMillis: Long = System.currentTimeMillis()): Snapshot? {
        val state = memoryCache ?: return null
        val age = nowMillis - state.fetchedAtMillis
        val freshDuration = combineDuration(defaultMaxAgeMillis, state.freshLifetimeMillis)
        val staleDuration = combineDuration(defaultStaleTtlMillis, state.staleLifetimeMillis)
        val staleBoundary = when {
            staleDuration == 0L && freshDuration == 0L -> 0L
            staleDuration == 0L -> freshDuration
            freshDuration == Long.MAX_VALUE -> staleDuration
            staleDuration == Long.MAX_VALUE -> Long.MAX_VALUE
            else -> freshDuration + staleDuration
        }

        val beyondTtl = when (staleBoundary) {
            Long.MAX_VALUE -> false
            else -> age > staleBoundary
        }

        if (beyondTtl) {
            Logger.w(TAG, "Discarding cached trust list beyond stale TTL")
            memoryCache = null
            return null
        }

        val stale = when {
            freshDuration == Long.MAX_VALUE -> false
            freshDuration == 0L -> age > 0L
            else -> age > freshDuration
        }
        return Snapshot(state.entries, state.revokedSerialNumbers, stale)
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

    private fun combineDuration(requested: Long, manifest: Long): Long {
        val requestedDuration = if (requested <= 0L) 0L else requested
        val manifestDuration = when (manifest) {
            Long.MAX_VALUE -> Long.MAX_VALUE
            else -> manifest
        }
        return when {
            requestedDuration == 0L -> 0L
            manifestDuration == 0L -> 0L
            manifestDuration == Long.MAX_VALUE -> requestedDuration
            requestedDuration == Long.MAX_VALUE -> manifestDuration
            else -> min(requestedDuration, manifestDuration)
        }
    }

    private suspend fun fetchTrustListWithRetry(): TrustListManifestVerifier.VerifiedManifest {
        var attempt = 0
        var nextDelayMillis = retryPolicy.initialDelayMillis
        var lastError: Throwable? = null

        while (attempt < retryPolicy.maxAttempts) {
            val attemptStart = System.currentTimeMillis()
            try {
                val payload = trustListApi.getTrustList()
                val verified = manifestVerifier.verify(payload)
                val duration = System.currentTimeMillis() - attemptStart
                StructuredEventLogger.log(
                    event = TRUST_LIST_REFRESH_EVENT,
                    timestampMs = attemptStart,
                    scanDurationMs = duration,
                    success = true,
                    reasonCode = if (attempt == 0) REASON_OK else REASON_RETRY_SUCCESS,
                )
                if (attempt > 0) {
                    Logger.i(TAG, "Trust list refresh succeeded after $attempt retries")
                }
                if (verified.freshLifetimeMillis == 0L && verified.staleLifetimeMillis == 0L) {
                    Logger.w(TAG, "Trust list manifest set zero cache lifetime; treating as immediate expiry")
                }
                return verified
            } catch (throwable: Throwable) {
                lastError = throwable
                val duration = System.currentTimeMillis() - attemptStart
                StructuredEventLogger.log(
                    event = TRUST_LIST_REFRESH_EVENT,
                    timestampMs = attemptStart,
                    scanDurationMs = duration,
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
                    put(KEY_FRESH_TTL, state.freshLifetimeMillis)
                    put(KEY_STALE_TTL, state.staleLifetimeMillis)
                    val entriesJson = JSONObject()
                    for ((issuer, certificate) in state.entries) {
                        entriesJson.put(issuer, certificate)
                    }
                    put(KEY_ENTRIES, entriesJson)
                    val revokedArray = JSONArray()
                    state.revokedSerialNumbers.forEach { revokedArray.put(it) }
                    put(KEY_REVOKED_SERIALS, revokedArray)
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

            val freshTtl = json.optLong(KEY_FRESH_TTL, Long.MAX_VALUE).coerceAtLeast(0L)
            val staleTtl = json.optLong(KEY_STALE_TTL, Long.MAX_VALUE).coerceAtLeast(0L)
            val revokedArray = json.optJSONArray(KEY_REVOKED_SERIALS)
            val revoked = mutableSetOf<String>()
            if (revokedArray != null) {
                for (index in 0 until revokedArray.length()) {
                    val value = revokedArray.optString(index, null) ?: continue
                    val normalized = value.trim()
                    if (normalized.isNotEmpty()) {
                        revoked.add(normalized)
                    }
                }
            }

            CacheState(
                entries = entries.toMap(),
                fetchedAtMillis = fetchedAt,
                freshLifetimeMillis = freshTtl,
                staleLifetimeMillis = staleTtl,
                revokedSerialNumbers = revoked,
            )
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
        private const val KEY_FRESH_TTL = "freshTtl"
        private const val KEY_STALE_TTL = "staleTtl"
        private const val KEY_REVOKED_SERIALS = "revokedSerials"
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
            val anchors = TrustListManifestVerifier.fromBase64Anchors(BuildConfig.TRUST_LIST_MANIFEST_ROOT_CERT)
            val manifestVerifier = TrustListManifestVerifier(anchors)
            return TrustListRepository(
                api = api,
                cacheDir = directory,
                defaultMaxAgeMillis = defaultMaxAgeMillis,
                defaultStaleTtlMillis = BuildConfig.TRUST_LIST_CACHE_STALE_TTL_MILLIS,
                ioDispatcher = ioDispatcher,
                initialBaseUrl = sanitizedBaseUrl,
                manifestVerifier = manifestVerifier,
            )
        }
    }
}
