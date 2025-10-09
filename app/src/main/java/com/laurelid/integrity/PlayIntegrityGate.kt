package com.laurelid.integrity

import android.content.Context
import android.util.Log
import com.laurelid.FeatureFlags

/**
 * Enforces Play Integrity before privileged admin features are exposed.
 */
object PlayIntegrityGate {
    private const val TAG = "PlayIntegrityGate"

    @Volatile
    private var helperFactory: (Context) -> PlayIntegrityVerdictProvider = { context ->
        PlayIntegrityHelper(context.applicationContext)
    }

    suspend fun isAdminAccessAllowed(context: Context): Boolean {
        if (!FeatureFlags.integrityGateEnabled) {
            Log.w(TAG, "Play Integrity gate disabled; allowing admin surface.")
            return true
        }
        val verdict = helperFactory(context.applicationContext).fetchVerdict()
        val allowed = verdict == PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY
        if (!allowed) {
            Log.w(TAG, "Play Integrity verdict=$verdict; bypassing admin block.")
            return verdict == PlayIntegrityVerdict.UNKNOWN
        }
        return allowed
    }

    fun setHelperFactoryForTesting(factory: (Context) -> PlayIntegrityVerdictProvider) {
        helperFactory = factory
    }

    fun resetForTesting() {
        helperFactory = { context -> PlayIntegrityHelper(context.applicationContext) }
    }
}

enum class PlayIntegrityVerdict {
    MEETS_DEVICE_INTEGRITY,
    FAILED_DEVICE_INTEGRITY,
    UNKNOWN,
}
