package com.laurelid.auth.deviceengagement

import com.laurelid.auth.DeviceResponseFormat
import com.laurelid.auth.MdocError
import com.laurelid.auth.MdocParseException
import com.laurelid.util.Logger

interface Transport {
    fun start()
    fun stop()
    fun receive(): TransportMessage
}

data class TransportMessage(
    val payload: ByteArray,
    val format: DeviceResponseFormat,
)

interface TransportFactory {
    fun create(deviceEngagement: DeviceEngagement): Transport
}

class DeviceEngagementTransportFactory : TransportFactory {
    override fun create(deviceEngagement: DeviceEngagement): Transport {
        deviceEngagement.nfc?.let { descriptor ->
            Logger.d(TAG, "Starting NFC transport for device engagement version ${deviceEngagement.version}")
            return NfcTransport(descriptor)
        }
        deviceEngagement.ble?.let { descriptor ->
            Logger.d(TAG, "Starting BLE transport for device engagement version ${deviceEngagement.version}")
            return BleTransport(descriptor)
        }
        throw MdocParseException(
            MdocError.UnsupportedTransport("No supported transports were advertised in the device engagement")
        )
    }

    companion object {
        private const val TAG = "TransportFactory"
    }
}

private abstract class BaseTransport(
    private val descriptor: TransportDescriptor,
    private val tag: String
) : Transport {

    private var started = false
    private var negotiatedFormat: DeviceResponseFormat? = null

    override fun start() {
        started = true
        negotiatedFormat = negotiateFormat()
    }

    override fun stop() {
        started = false
        negotiatedFormat = null
    }

    override fun receive(): TransportMessage {
        check(started) { "$tag transport must be started before receiving" }
        val format = negotiatedFormat
            ?: throw MdocParseException(MdocError.NegotiationFailure("Transport did not negotiate a response format"))
        val payload = descriptor.responses[format]
            ?: throw MdocParseException(
                MdocError.UnsupportedResponseFormat("Negotiated format ${format.name} missing for ${descriptor.type}")
            )
        return TransportMessage(payload = payload, format = format)
    }

    private fun negotiateFormat(): DeviceResponseFormat {
        val advertised = descriptor.supportedFormats.ifEmpty { descriptor.responses.keys.toList() }
        val priority = listOf(DeviceResponseFormat.COSE_SIGN1, DeviceResponseFormat.SD_JWT)
        for (candidate in priority) {
            if (advertised.contains(candidate) && descriptor.responses.containsKey(candidate)) {
                Logger.d(tag, "Negotiated device response format ${candidate.name}")
                return candidate
            }
        }
        val fallback = advertised.firstOrNull { descriptor.responses.containsKey(it) }
            ?: descriptor.responses.keys.firstOrNull()
            ?: throw MdocParseException(
                MdocError.UnsupportedResponseFormat("No matching device response format advertised by ${descriptor.type}")
            )
        Logger.d(tag, "Falling back to advertised format ${fallback.name}")
        return fallback
    }
}

private class NfcTransport(descriptor: TransportDescriptor) : BaseTransport(descriptor, TAG) {
    companion object {
        private const val TAG = "NfcTransport"
    }
}

private class BleTransport(descriptor: TransportDescriptor) : BaseTransport(descriptor, TAG) {
    companion object {
        private const val TAG = "BleTransport"
    }
}
