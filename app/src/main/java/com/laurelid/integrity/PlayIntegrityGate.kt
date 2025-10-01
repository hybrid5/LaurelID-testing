package com.laurelid.integrity

import android.content.Context
import android.util.Log

/**
 * Handles Play Integrity verdicts before privileged admin features are exposed.
 *
 * The current implementation is a synchronous stub that always returns [PlayIntegrityVerdict.ALLOWED]
 * but the call site is wired for the real, asynchronous Play Integrity API.
 */
object PlayIntegrityGate {
    private const val TAG = "PlayIntegrityGate"

    fun isAdminAccessAllowed(context: Context): Boolean {
        val verdict = StubPlayIntegrityCheck(context.applicationContext).fetchVerdict()
        val allowed = verdict == PlayIntegrityVerdict.ALLOWED
        if (!allowed) {
            Log.w(TAG, "Blocking admin surface due to Play Integrity verdict: $verdict")
        }
        return allowed
    }
}

/** Result surface for future Play Integrity attestation integration. */
enum class PlayIntegrityVerdict {
    ALLOWED,
    BLOCKED
}

/**
 * Placeholder implementation that will later perform a server-backed Play Integrity attestation.
 */
class StubPlayIntegrityCheck(private val context: Context) {
    fun fetchVerdict(): PlayIntegrityVerdict {
        Log.i(TAG, "Play Integrity stub invoked for package: ${context.packageName}")
        return PlayIntegrityVerdict.ALLOWED
    }

    private companion object {
        private const val TAG = "PlayIntegrityStub"
    }
}
