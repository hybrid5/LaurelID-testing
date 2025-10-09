package com.laurelid.deviceengagement

/**
 * Placeholder for Bluetooth Low Energy transport; Verify with Wallet currently mandates NFC or QR,
 * but ISO/IEC 18013-7 §8.4 defines BLE handover for future expansion. 【ISO18013-7§8.4】
 */
class BleTransport private constructor(
    descriptor: TransportDescriptor,
) : Transport(descriptor, TAG) {

    override suspend fun onStarted() {
        // TODO(PROD): initiate BLE GATT connection and stream cipher payload with backpressure.
    }

    companion object {
        private const val TAG = "BleTransport"

        fun fromDescriptor(descriptor: TransportDescriptor): BleTransport = BleTransport(descriptor)
    }
}
