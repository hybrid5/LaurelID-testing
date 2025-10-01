package com.laurelid.integrity

import android.content.Context
import android.util.Log
import com.laurelid.BuildConfig

/**
 * Enforces Play Integrity before privileged admin features are exposed.
 */
object PlayIntegrityGate {
    private const val TAG = "PlayIntegrityGate"

    @Volatile
    private var helperFactory: (Context) -> PlayIntegrityVerdictProvider = { context ->
        PlayIntegrityHelper(context.applicationContext)
    }
    @Volatile
    private var flavorOverride: String? = null

    suspend fun isAdminAccessAllowed(context: Context): Boolean {
        val flavor = flavorOverride ?: BuildConfig.FLAVOR
        if (flavor == "staging") {
            Log.i(TAG, "Bypassing Play Integrity gate in staging build.")
            return true
        }

        val verdict = helperFactory(context.applicationContext).fetchVerdict()
        val allowed = verdict == PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY
        if (!allowed) {
            Log.w(TAG, "Blocking admin surface due to Play Integrity verdict: $verdict")
        }
        return allowed
    }

    fun setHelperFactoryForTesting(factory: (Context) -> PlayIntegrityVerdictProvider) {
        helperFactory = factory
    }

    fun overrideFlavorForTesting(flavor: String?) {
        flavorOverride = flavor
    }

    fun resetForTesting() {
        helperFactory = { context -> PlayIntegrityHelper(context.applicationContext) }
        flavorOverride = null
    }
}

enum class PlayIntegrityVerdict {
    MEETS_DEVICE_INTEGRITY,
    FAILED_DEVICE_INTEGRITY,
    UNKNOWN,
}
