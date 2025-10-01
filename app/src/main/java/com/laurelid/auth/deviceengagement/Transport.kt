package com.laurelid.auth.deviceengagement

import com.laurelid.util.Logger

interface Transport {
    fun start()
    fun stop()
    fun receive(): ByteArray
}

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
        throw IllegalArgumentException("No supported transports were advertised in the device engagement")
    }

    companion object {
        private const val TAG = "TransportFactory"
    }
}

private abstract class BaseTransport(
    descriptor: TransportDescriptor,
    private val tag: String
) : Transport {

    private val queue = ArrayDeque(descriptor.messages)
    private var started = false

    override fun start() {
        started = true
    }

    override fun stop() {
        queue.clear()
        started = false
    }

    override fun receive(): ByteArray {
        check(started) { "$tag transport must be started before receiving" }
        return queue.removeFirstOrNull()
            ?: throw IllegalStateException("$tag transport did not provide a device response")
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
