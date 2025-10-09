package com.laurelid.deviceengagement

import com.laurelid.mdoc.DeviceResponseFormat
import com.laurelid.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Abstraction for transport retrieval methods defined in ISO/IEC 18013-7 §7.3. 【ISO18013-7§7.3】
 * Each transport advertises supported response encodings defined in ISO/IEC 18013-5 §9. 【ISO18013-5§9】
 */
sealed class Transport(
    protected val descriptor: TransportDescriptor,
    private val tag: String,
) {
    private val started = AtomicBoolean(false)
    private val message = CompletableDeferred<TransportMessage>()

    suspend fun start() {
        if (!started.compareAndSet(false, true)) return
        onStarted()
    }

    suspend fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { onStopped() }
        if (!message.isCompleted) message.cancel()
    }

    suspend fun receive(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): TransportMessage = try {
        withTimeout(timeoutMillis) { message.await() }
    } catch (timeout: TimeoutCancellationException) {
        throw TransportException.Timeout("Timed out waiting for ${descriptor.type} response", timeout)
    }

    fun handshakePayload(): ByteArray? = descriptor.engagementPayload

    protected fun complete(result: TransportMessage) {
        if (!message.isCompleted) {
            Logger.d(tag, "Transport completed with format=${result.format}")
            message.complete(result)
        }
    }

    protected fun fail(error: Throwable) {
        if (!message.isCompleted) {
            message.completeExceptionally(error)
        }
    }

    protected open suspend fun onStarted() {}
    protected open suspend fun onStopped() {}

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

/**
 * QR/Web transport used for camera-based engagement handoff. 【ISO18013-7§8.2】
 */
class QrTransport private constructor(descriptor: TransportDescriptor) : Transport(descriptor, TAG) {

    fun currentFormat(): DeviceResponseFormat = descriptor.supportedFormats.firstOrNull()
        ?: DeviceResponseFormat.COSE_SIGN1

    companion object {
        private const val TAG = "QrTransport"
        fun fromDescriptor(descriptor: TransportDescriptor): QrTransport = QrTransport(descriptor)
    }
}

/** Wrapper for the wallet's encrypted response envelope (ISO/IEC 18013-5 §9.4). */
data class TransportMessage(
    val payload: ByteArray,
    val format: DeviceResponseFormat,
    val transcript: ByteArray?,
    val engagementNonce: ByteArray?,
)

/** Raised when a transport fails to deliver a wallet response. */
sealed class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(message: String, cause: Throwable? = null) : TransportException(message, cause)
    class Unsupported(message: String) : TransportException(message)
    class Protocol(message: String, cause: Throwable? = null) : TransportException(message, cause)
}
