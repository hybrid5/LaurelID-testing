package com.laurelid.network

import android.content.Context
import com.laurelid.util.Logger
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TrustListRepository(
    private val api: TrustListApi,
    private val cacheDir: File,
    private val defaultMaxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
    private var memoryCache: CacheState? = null

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    suspend fun getOrRefresh(nowMillis: Long): Snapshot = getOrRefresh(nowMillis, defaultMaxAgeMillis)

    suspend fun getOrRefresh(nowMillis: Long, maxAgeMillis: Long): Snapshot = mutex.withLock {
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
            val remote = api.getTrustList()
            val sanitized = remote.toMap()
            val updatedState = CacheState(sanitized, nowMillis)
            memoryCache = updatedState
            persist(updatedState)
            Logger.i(TAG, "Trust list refreshed with ${sanitized.size} entries")
            Snapshot(sanitized, stale = false)
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to refresh trust list, falling back to cache", throwable)
            if (current != null) {
                val stale = if (maxAgeMillis <= 0L) {
                    true
                } else {
                    nowMillis - current.fetchedAtMillis > maxAgeMillis
                }
                Logger.w(TAG, "Serving cached trust list (stale=$stale)")
                Snapshot(current.entries, stale = stale)
            } else {
                throw throwable
            }
        }
    }

    fun cached(): Snapshot? {
        val state = memoryCache ?: return null
        val stale = if (defaultMaxAgeMillis <= 0L) {
            true
        } else {
            System.currentTimeMillis() - state.fetchedAtMillis > defaultMaxAgeMillis
        }
        return Snapshot(state.entries, stale)
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

        fun create(
            context: Context,
            api: TrustListApi,
            defaultMaxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): TrustListRepository {
            val directory = File(context.filesDir, CACHE_DIRECTORY)
            return TrustListRepository(api, directory, defaultMaxAgeMillis, ioDispatcher)
        }
    }
}
