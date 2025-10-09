package com.laurelid.auth.deviceengagement

import com.laurelid.auth.DeviceResponseFormat
import com.laurelid.auth.MdocError
import com.laurelid.auth.MdocParseException
import com.laurelid.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Sealed transport hierarchy for Verify-with-Wallet engagements (QR/Web, NFC, BLE).
 */
sealed class Transport(
    private val descriptor: TransportDescriptor,
    private val tag: String,
) {
    private val started = AtomicBoolean(false)
    private var negotiated: DeviceResponseFormat? = null
    private var message: CompletableDeferred<TransportMessage>? = null

    suspend fun start() {
        if (!started.compareAndSet(false, true)) return
        negotiated = negotiateFormat()
        message = CompletableDeferred<TransportMessage>().apply { complete(buildMessage()) }
        onStarted()
    }

    suspend fun stop() {
        if (!started.compareAndSet(true, false)) return
        negotiated = null
        message?.cancel()
        message = null
        onStopped()
    }

    suspend fun receive(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): TransportMessage {
        val deferred = message ?: throw IllegalStateException("Transport not started")
        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw MdocParseException(MdocError.Timeout("Timed out waiting for ${descriptor.type} response"), timeout)
        }
    }

    protected open suspend fun onStarted() {}
    protected open suspend fun onStopped() {}

    private fun buildMessage(): TransportMessage {
        val format = negotiated
            ?: throw MdocParseException(MdocError.NegotiationFailure("No negotiated response format"))
        val payload = descriptor.responses[format]
            ?: throw MdocParseException(
                MdocError.UnsupportedResponseFormat("Negotiated format ${format.name} missing for ${descriptor.type}")
            )
        return TransportMessage(payload = payload, format = format, transcript = descriptor.sessionTranscript)
    }

    private fun negotiateFormat(): DeviceResponseFormat {
        val advertised = descriptor.supportedFormats.ifEmpty { descriptor.responses.keys.toList() }
        for (candidate in PREFERRED_FORMATS) {
            if (advertised.contains(candidate) && descriptor.responses.containsKey(candidate)) {
                Logger.d(tag, "Negotiated device response format ${candidate.name}")
                return candidate
            }
        }
        val fallback = advertised.firstOrNull { descriptor.responses.containsKey(it) }
            ?: descriptor.responses.keys.firstOrNull()
            ?: throw MdocParseException(
                MdocError.UnsupportedResponseFormat("No matching response format advertised for ${descriptor.type}")
            )
        Logger.d(tag, "Falling back to advertised format ${fallback.name}")
        return fallback
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private val PREFERRED_FORMATS = listOf(DeviceResponseFormat.COSE_SIGN1, DeviceResponseFormat.SD_JWT)
    }
}

class WebTransport private constructor(descriptor: TransportDescriptor) : Transport(descriptor, TAG) {
    companion object {
        private const val TAG = "WebTransport"
        fun fromDescriptor(descriptor: TransportDescriptor): WebTransport = WebTransport(descriptor)
    }
}

class NfcTransport private constructor(descriptor: TransportDescriptor) : Transport(descriptor, TAG) {
    companion object {
        private const val TAG = "NfcTransport"
        fun fromDescriptor(descriptor: TransportDescriptor): NfcTransport = NfcTransport(descriptor)
    }
}

class BleTransport private constructor(descriptor: TransportDescriptor) : Transport(descriptor, TAG) {
    companion object {
        private const val TAG = "BleTransport"
        fun fromDescriptor(descriptor: TransportDescriptor): BleTransport = BleTransport(descriptor)
    }
}

/** Wrapper for COSE payloads emitted by the wallet. */
data class TransportMessage(
    val payload: ByteArray,
    val format: DeviceResponseFormat,
    val transcript: ByteArray?,
)

interface TransportFactory {
    fun create(deviceEngagement: DeviceEngagement): Transport
}

@Singleton
class DeviceEngagementTransportFactory @Inject constructor() : TransportFactory {
    override fun create(deviceEngagement: DeviceEngagement): Transport {
        deviceEngagement.web?.let { return WebTransport.fromDescriptor(it) }
        deviceEngagement.nfc?.let { return NfcTransport.fromDescriptor(it) }
        deviceEngagement.ble?.let { return BleTransport.fromDescriptor(it) }
        throw MdocParseException(
            MdocError.UnsupportedTransport("No supported transports were advertised in the device engagement")
        )
    }
}

