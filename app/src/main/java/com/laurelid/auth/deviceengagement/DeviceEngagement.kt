package com.laurelid.auth.deviceengagement

import com.laurelid.auth.DeviceResponseFormat

data class DeviceEngagement(
    val version: Int,
    val nfc: TransportDescriptor?,
    val ble: TransportDescriptor?,
)

data class TransportDescriptor(
    val type: TransportType,
    val supportedFormats: List<DeviceResponseFormat>,
    val responses: Map<DeviceResponseFormat, ByteArray>,
    val sessionTranscript: ByteArray? = null,
)

enum class TransportType {
    NFC,
    BLE,
}
