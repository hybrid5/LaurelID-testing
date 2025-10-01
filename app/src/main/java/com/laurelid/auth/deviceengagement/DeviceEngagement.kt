package com.laurelid.auth.deviceengagement

data class DeviceEngagement(
    val version: Int,
    val nfc: TransportDescriptor?,
    val ble: TransportDescriptor?
)

data class TransportDescriptor(
    val messages: List<ByteArray>
)
