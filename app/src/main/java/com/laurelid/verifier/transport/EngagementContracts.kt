package com.laurelid.verifier.transport

/**
 * Abstraction over the different device engagement transports (QR, NFC, BLE).
 * Implementations must surface a session transcript that is later bound into the
 * device signature verification step.
 */
interface EngagementTransport {
    /** Starts advertising a verification request and returns the established session. */
    suspend fun start(): EngagementSession

    /** Stops the underlying transport and releases hardware resources. */
    suspend fun stop()
}

/**
 * Represents an active device engagement session. The transcript captures all bytes exchanged
 * during the bootstrap and feeds into device binding.
 */
data class EngagementSession(
    val sessionId: String,
    val transcript: ByteArray,
    val peerInfo: ByteArray? = null,
)
