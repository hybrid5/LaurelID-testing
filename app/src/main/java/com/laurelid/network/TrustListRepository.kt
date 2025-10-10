package com.laurelid.network

import android.content.Context
import com.laurelid.BuildConfig
import com.laurelid.observability.StructuredEventLogger
import com.laurelid.util.Logger
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

open class TrustListRepository internal constructor(
    api: TrustListApi,
    private val cacheDir: File,
    private val context: Context? = null,
    private val defaultMaxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val defaultStaleTtlMillis: Long = DEFAULT_STALE_TTL_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialBaseUrl: String? = null,
    private val delayProvider: suspend (Long) -> Unit = { delay(it) },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val manifestVerifier: TrustListManifestVerifier,
    cacheStorage: TrustListCacheStorage? = null,
    private val seedLoader: TrustListSeedLoader? = null,
    private val allowPlaintextCacheForTests: Boolean = BuildConfig.ALLOW_PLAINTEXT_TRUST_CACHE_FOR_TESTS,
) {

    private val cacheFile = File(cacheDir, CACHE_FILE_NAME)
    private val cacheStorage: TrustListCacheStorage = cacheStorage ?: createDefaultCacheStorage()

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
        val source: CacheSource,
    )

    private enum class CacheSource {
        NETWORK,
        DISK,
        SEED,
    }

    private val mutex = Mutex()
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
        val current = ensureCacheLoaded(nowMillis)
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
            val verified = remote.manifest
            val sanitized = verified.entries
            val updatedState = CacheState(
                entries = sanitized,
                fetchedAtMillis = nowMillis,
                freshLifetimeMillis = verified.freshLifetimeMillis,
                staleLifetimeMillis = verified.staleLifetimeMillis,
                revokedSerialNumbers = verified.revokedSerialNumbers,
                source = CacheSource.NETWORK,
            )
            memoryCache = updatedState
            persist(nowMillis, remote)
            Logger.i(TAG, "Trust list refreshed with ${sanitized.size} entries")
            Snapshot(sanitized, verified.revokedSerialNumbers, stale = false)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to refresh trust list, falling back to cache", throwable)
            if (current != null) {
                val fallbackFresh = when (current.source) {
                    CacheSource.SEED -> 0L
                    else -> freshDuration ?: combineDuration(maxAgeMillis, current.freshLifetimeMillis)
                }
                val staleDuration = when (current.source) {
                    CacheSource.SEED -> Long.MAX_VALUE
                    else -> combineDuration(staleTtlMillis, current.staleLifetimeMillis)
                }
                val allowStale = when {
                    current.source == CacheSource.SEED -> true
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
                    current.source == CacheSource.SEED -> true
                    fallbackFresh == Long.MAX_VALUE -> false
                    fallbackFresh == 0L -> true
                    else -> age > fallbackFresh
                }
                val event = if (current.source == CacheSource.SEED) {
                    TRUST_LIST_SEED_SERVED_EVENT
                } else {
                    TRUST_LIST_STALE_CACHE_SERVED_EVENT
                }
                Logger.w(
                    TAG,
                    "Serving cached trust list (stale=$stale, source=${current.source})",
                )
                StructuredEventLogger.log(
                    event = event,
                    timestampMs = System.currentTimeMillis(),
                    success = current.source != CacheSource.SEED && !stale,
                    reasonCode = when {
                        current.source == CacheSource.SEED -> REASON_SEED_USED
                        stale -> REASON_STALE_CACHE
                        else -> REASON_CACHE_FRESH
                    },
                    trustStale = current.source == CacheSource.SEED || stale,
                )
                val snapshotStale = current.source == CacheSource.SEED || stale
                Snapshot(current.entries, current.revokedSerialNumbers, stale = snapshotStale)
            } else {
                throw throwable
            }
        }
    }

    open fun cached(nowMillis: Long = System.currentTimeMillis()): Snapshot? {
        val state = memoryCache ?: return null
        if (state.source == CacheSource.SEED) {
            return Snapshot(state.entries, state.revokedSerialNumbers, stale = true)
        }
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

    open suspend fun updateEndpoint(newApi: TrustListApi, newBaseUrl: String) {
        val sanitizedUrl = TrustListEndpointPolicy.requireEndpointAllowed(newBaseUrl)
        withContext(ioDispatcher) {
            mutex.withLock {
                trustListApi = newApi
                baseUrl = sanitizedUrl
                memoryCache = null
                clearDiskCacheLocked()
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

    private suspend fun fetchTrustListWithRetry(): VerifiedTrustListPayload {
        var attempt = 0
        var nextDelayMillis = retryPolicy.initialDelayMillis
        var lastError: Throwable? = null

        while (attempt < retryPolicy.maxAttempts) {
            val attemptStart = System.currentTimeMillis()
            try {
                val response = trustListApi.getTrustList()
                val verified = manifestVerifier.verify(response)
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
                return VerifiedTrustListPayload(response = response, manifest = verified)
            } catch (throwable: Throwable) {
                lastError = throwable
                val duration = System.currentTimeMillis() - attemptStart
                val reason = failureReasonCode(throwable)
                StructuredEventLogger.log(
                    event = TRUST_LIST_REFRESH_EVENT,
                    timestampMs = attemptStart,
                    scanDurationMs = duration,
                    success = false,
                    reasonCode = reason,
                )
                Logger.w(TAG, "Trust list refresh attempt ${attempt + 1} failed", throwable)
                attempt += 1
                if (attempt >= retryPolicy.maxAttempts) {
                    break
                }

                val sleepMillis = nextDelayMillis.coerceAtMost(retryPolicy.maxDelayMillis)
                if (sleepMillis > 0L) {
                    StructuredEventLogger.log(
                        event = TRUST_LIST_REFRESH_RETRY_EVENT,
                        timestampMs = System.currentTimeMillis(),
                        scanDurationMs = sleepMillis,
                        success = false,
                        reasonCode = reason,
                    )
                    delayProvider.invoke(sleepMillis)
                }
                nextDelayMillis = computeNextDelay(nextDelayMillis)
            }
        }

        StructuredEventLogger.log(
            event = TRUST_LIST_REFRESH_EVENT,
            timestampMs = System.currentTimeMillis(),
            success = false,
            reasonCode = REASON_RETRIES_EXHAUSTED,
        )
        throw lastError ?: IllegalStateException("Unable to refresh trust list")
    }

    private suspend fun ensureCacheLoaded(nowMillis: Long): CacheState? {
        val existing = memoryCache
        if (existing != null) {
            return existing
        }

        val diskState = readFromDisk()
        if (diskState != null) {
            memoryCache = diskState
            return diskState
        }

        val seedState = loadSeed(nowMillis)
        if (seedState != null) {
            memoryCache = seedState
            return seedState
        }

        return null
    }

    private suspend fun persist(fetchedAtMillis: Long, payload: VerifiedTrustListPayload) {
        val currentBaseUrl = baseUrl
        withContext(ioDispatcher) {
            try {
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val response = payload.response
                val json = JSONObject().apply {
                    put(KEY_FETCHED_AT, fetchedAtMillis)
                    if (!currentBaseUrl.isNullOrBlank()) {
                        put(KEY_BASE_URL, currentBaseUrl)
                    }
                    put(KEY_MANIFEST, response.manifest)
                    put(KEY_SIGNATURE, response.signature)
                    val chainArray = JSONArray()
                    response.certificateChain.forEach { certificate ->
                        chainArray.put(certificate)
                    }
                    put(KEY_CERTIFICATE_CHAIN, chainArray)
                    put(KEY_MANIFEST_CHECKSUM, computeChecksum(response.manifest))
                    payload.manifest.manifestVersion?.let { version ->
                        if (version.isNotBlank()) {
                            put(KEY_MANIFEST_VERSION, version)
                        }
                    }
                }
                cacheStorage.write(json.toString())
            } catch (throwable: Throwable) {
                Logger.e(TAG, "Unable to persist trust list cache", throwable)
            }
        }
    }

    private suspend fun readFromDisk(): CacheState? = withContext(ioDispatcher) {
        val raw = try {
            cacheStorage.read()
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to read trust list cache from storage", throwable)
            return@withContext null
        }
        if (raw.isNullOrBlank()) {
            return@withContext null
        }

        try {
            val json = JSONObject(raw)
            val fetchedAt = json.optLong(KEY_FETCHED_AT, -1L)
            if (fetchedAt <= 0L) {
                return@withContext null
            }
            val cachedBaseUrl = json.optString(KEY_BASE_URL, null)
            val currentBaseUrl = baseUrl
            if (!TrustListEndpointPolicy.endpointsMatch(cachedBaseUrl, currentBaseUrl)) {
                Logger.w(TAG, "Discarding trust list cache for mismatched endpoint")
                return@withContext null
            }

            val manifest = json.optString(KEY_MANIFEST, null)?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val signature = json.optString(KEY_SIGNATURE, null)?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val chainArray = json.optJSONArray(KEY_CERTIFICATE_CHAIN) ?: return@withContext null
            val expectedChecksum = json.optString(KEY_MANIFEST_CHECKSUM, null)?.takeIf { it.isNotBlank() }
                ?: run {
                    Logger.w(TAG, "Stored trust list cache missing checksum; deleting cache")
                    cacheStorage.delete()
                    return@withContext null
                }
            val expectedVersion = json.optString(KEY_MANIFEST_VERSION, null)?.takeIf { it.isNotBlank() }
                ?: run {
                    Logger.w(TAG, "Stored trust list cache missing version; deleting cache")
                    cacheStorage.delete()
                    return@withContext null
                }
            val certificateChain = mutableListOf<String>()
            for (index in 0 until chainArray.length()) {
                val value = chainArray.optString(index, null)?.trim()
                if (!value.isNullOrEmpty()) {
                    certificateChain.add(value)
                }
            }

            val response = TrustListResponse(
                manifest = manifest,
                signature = signature,
                certificateChain = certificateChain,
            )

            val verified = try {
                manifestVerifier.verify(response)
            } catch (security: SecurityException) {
                Logger.e(TAG, "Stored trust list manifest failed validation; deleting cache", security)
                StructuredEventLogger.log(
                    event = TRUST_LIST_CACHE_VALIDATION_FAILED_EVENT,
                    success = false,
                    reasonCode = failureReasonCode(security),
                )
                cacheStorage.delete()
                return@withContext null
            }

            val actualChecksum = computeChecksum(response.manifest)
            if (expectedChecksum != actualChecksum) {
                Logger.e(TAG, "Stored trust list checksum mismatch; deleting cache")
                StructuredEventLogger.log(
                    event = TRUST_LIST_CACHE_VALIDATION_FAILED_EVENT,
                    success = false,
                    reasonCode = REASON_CHECKSUM_MISMATCH,
                )
                cacheStorage.delete()
                return@withContext null
            }

            val actualVersion = verified.manifestVersion
            if (actualVersion == null || actualVersion != expectedVersion) {
                Logger.e(TAG, "Stored trust list version mismatch; deleting cache")
                StructuredEventLogger.log(
                    event = TRUST_LIST_CACHE_VALIDATION_FAILED_EVENT,
                    success = false,
                    reasonCode = REASON_VERSION_MISMATCH,
                )
                cacheStorage.delete()
                return@withContext null
            }

            CacheState(
                entries = verified.entries,
                fetchedAtMillis = fetchedAt,
                freshLifetimeMillis = verified.freshLifetimeMillis,
                staleLifetimeMillis = verified.staleLifetimeMillis,
                revokedSerialNumbers = verified.revokedSerialNumbers,
                source = CacheSource.DISK,
            )
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to read trust list cache from disk", throwable)
            null
        }
    }

    private suspend fun loadSeed(nowMillis: Long): CacheState? {
        val loader = seedLoader ?: return null
        return withContext(ioDispatcher) {
            try {
                val seed = loader.load(nowMillis, baseUrl)
                seed?.let {
                    CacheState(
                        entries = it.payload.manifest.entries,
                        fetchedAtMillis = it.generatedAtMillis,
                        freshLifetimeMillis = 0L,
                        staleLifetimeMillis = it.staleTtlMillis,
                        revokedSerialNumbers = it.payload.manifest.revokedSerialNumbers,
                        source = CacheSource.SEED,
                    )
                }
            } catch (throwable: Throwable) {
                Logger.e(TAG, "Unable to load seed trust list", throwable)
                null
            }
        }
    }

    private fun clearDiskCacheLocked() {
        try {
            cacheStorage.delete()
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to clear trust list cache on endpoint change", throwable)
        }
    }

    private fun computeChecksum(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        private const val TAG = "TrustListRepo"
        private const val CACHE_DIRECTORY = "trust_list"
        private const val CACHE_FILE_NAME = "trust_list.json"
        private const val KEY_FETCHED_AT = "fetchedAt"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_MANIFEST = "manifest"
        private const val KEY_SIGNATURE = "signature"
        private const val KEY_CERTIFICATE_CHAIN = "certificateChain"
        private const val KEY_MANIFEST_CHECKSUM = "checksum"
        private const val KEY_MANIFEST_VERSION = "version"
        private val DEFAULT_MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(12)
        private val DEFAULT_STALE_TTL_MILLIS = TimeUnit.DAYS.toMillis(3)
        internal const val DEFAULT_MAX_ATTEMPTS = 3
        private const val DEFAULT_INITIAL_DELAY_MILLIS = 500L
        private const val DEFAULT_MAX_DELAY_MILLIS = 5_000L
        private const val DEFAULT_BACKOFF_MULTIPLIER = 2.0
        private const val TRUST_LIST_REFRESH_EVENT = "trust_list_refresh"
        private const val TRUST_LIST_REFRESH_RETRY_EVENT = "trust_list_refresh_retry"
        private const val TRUST_LIST_STALE_CACHE_SERVED_EVENT = "trust_list_stale_cache_served"
        private const val TRUST_LIST_SEED_SERVED_EVENT = "trust_list_seed_served"
        private const val TRUST_LIST_CACHE_EXPIRED_EVENT = "trust_list_cache_expired"
        private const val TRUST_LIST_CACHE_VALIDATION_FAILED_EVENT = "trust_list_cache_validation_failed"
        private const val REASON_OK = "OK"
        private const val REASON_RETRY_SUCCESS = "RETRY_SUCCESS"
        private const val REASON_RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED"
        private const val REASON_STALE_CACHE = "STALE_CACHE"
        private const val REASON_CACHE_FRESH = "CACHE_FRESH"
        private const val REASON_STALE_EXPIRED = "STALE_TTL_EXCEEDED"
        private const val REASON_CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH"
        private const val REASON_VERSION_MISMATCH = "VERSION_MISMATCH"
        private const val REASON_SEED_USED = "SEED_FALLBACK"
        private const val DEFAULT_SEED_ASSET_PATH = "trust_seed.json"

        fun create(
            context: Context,
            api: TrustListApi,
            defaultMaxAgeMillis: Long = BuildConfig.TRUST_LIST_CACHE_MAX_AGE_MILLIS,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            baseUrl: String = TrustListEndpointPolicy.defaultBaseUrl,
        ): TrustListRepository {
            val directory = File(context.filesDir, CACHE_DIRECTORY)
            val cacheFile = File(directory, CACHE_FILE_NAME)
            val sanitizedBaseUrl = TrustListEndpointPolicy.requireEndpointAllowed(baseUrl)
            val anchors = TrustListManifestVerifier.fromBase64Anchors(BuildConfig.TRUST_LIST_MANIFEST_ROOT_CERT)
            val manifestVerifier = TrustListManifestVerifier(anchors)
            val seedStorage = AssetTrustListSeedStorage(context, DEFAULT_SEED_ASSET_PATH)
            val seedLoader = TrustListSeedLoader(seedStorage, manifestVerifier)
            return TrustListRepository(
                api = api,
                cacheDir = directory,
                context = context,
                defaultMaxAgeMillis = defaultMaxAgeMillis,
                defaultStaleTtlMillis = BuildConfig.TRUST_LIST_CACHE_STALE_TTL_MILLIS,
                ioDispatcher = ioDispatcher,
                initialBaseUrl = sanitizedBaseUrl,
                manifestVerifier = manifestVerifier,
                seedLoader = seedLoader,
            )
        }
    }

    private fun createDefaultCacheStorage(): TrustListCacheStorage {
        if (context != null) {
            return EncryptedTrustListCacheStorage(context, cacheFile)
        }
        if (allowPlaintextCacheForTests) {
            return PlainTextTrustListCacheStorage(cacheFile)
        }
        throw IllegalStateException(
            "Encrypted trust list cache requires application context; enable plaintext cache explicitly for tests."
        )
    }
}

internal class PlainTextTrustListCacheStorage(private val file: File) : TrustListCacheStorage {
    override fun read(): String? {
        if (!file.exists()) {
            return null
        }
        return file.readText()
    }

    override fun write(contents: String) {
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        file.writeText(contents)
    }

    override fun delete() {
        if (file.exists() && !file.delete()) {
            file.writeText("")
        }
    }
}
