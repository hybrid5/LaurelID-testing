package com.laurelid.network

import com.laurelid.util.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrustListRepository(private val api: TrustListApi) {
    private val mutex = Mutex()
    @Volatile
    private var cache: Map<String, String>? = null
    @Volatile
    private var lastUpdatedMillis: Long = 0L

    suspend fun refresh(): Map<String, String> = mutex.withLock {
        try {
            val remote = api.getTrustList()
            cache = remote
            lastUpdatedMillis = System.currentTimeMillis()
            Logger.i(TAG, "Trust list refreshed with ${remote.size} entries")
            remote // This is the return value for the mutex.withLock block
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Failed to refresh trust list", throwable)
            throw throwable // Rethrow to signal failure
        }
        // Removed unreachable code that was here
    }

    suspend fun get(): Map<String, String> = mutex.withLock {
        cache ?: run { // If cache is null, execute this block
            try {
                val remote = api.getTrustList()
                cache = remote
                lastUpdatedMillis = System.currentTimeMillis()
                Logger.i(TAG, "Loaded trust list with ${remote.size} entries")
                remote // Return the fetched remote list
            } catch (throwable: Throwable) {
                Logger.e(TAG, "Unable to load trust list, falling back to cache (which is null here)", throwable)
                // If cache was initially null and fetch fails, rethrow.
                // The original 'cache ?: throw throwable' would always throw here since cache is null in this run block.
                throw throwable
            }
        }
    }

    suspend fun getOrRefresh(maxAgeMillis: Long): Map<String, String> = mutex.withLock {
        val now = System.currentTimeMillis()
        val currentCache = cache // Use a local val for consistent check
        val isCacheStale = maxAgeMillis > 0 && (currentCache == null || now - lastUpdatedMillis > maxAgeMillis)

        if (isCacheStale) {
            Logger.i(TAG, "Trust list is stale or missing. Attempting refresh (policy ${maxAgeMillis}ms).")
            try {
                val remote = api.getTrustList()
                cache = remote
                lastUpdatedMillis = now
                Logger.i(TAG, "Trust list refreshed with ${remote.size} entries.")
                return@withLock remote // Return the newly fetched list
            } catch (throwable: Throwable) {
                Logger.e(TAG, "Trust list refresh failed. Using cached copy if available.", throwable)
                if (currentCache != null) {
                    Logger.i(TAG, "Returning stale but available cached trust list.")
                    return@withLock currentCache // Return stale cache as fallback
                }
                Logger.e(TAG, "No cached trust list available after refresh failure.")
                throw throwable // No cache and refresh failed, rethrow
            }
        }

        // If not stale, and cache exists, return it
        if (currentCache != null) {
            Logger.i(TAG, "Returning current (non-stale) cached trust list.")
            return@withLock currentCache
        }

        // Cache is null and it wasn't considered stale (e.g., maxAgeMillis <= 0 or first call)
        // This path is similar to the 'get()' method's initial load.
        Logger.i(TAG, "No cached trust list, attempting initial load.")
        return@withLock try {
            val remote = api.getTrustList()
            cache = remote
            lastUpdatedMillis = now // Record time for initial load as well
            Logger.i(TAG, "Loaded trust list initially with ${remote.size} entries.")
            remote
        } catch (throwable: Throwable) {
            Logger.e(TAG, "Unable to load trust list initially.", throwable)
            throw throwable
        }
        // Removed malformed/duplicated code that was here
    }

    fun cached(): Map<String, String>? = cache

    private companion object {
        private const val TAG = "TrustListRepo"
    }
}
