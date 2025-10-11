package com.laurelid.deviceengagement

import com.laurelid.mdoc.DeviceResponseFormat

/**
 * Represents the parsed device engagement object handed to the kiosk. 【ISO18013-7§7.2】
 */
data class DeviceEngagement(
    val version: Int,
    val qr: TransportDescriptor? = null,
    val nfc: TransportDescriptor? = null,
)

/**
 * Metadata advertised by a wallet for a specific retrieval method. 【ISO18013-7§7.3】
 */
data class TransportDescriptor(
    val type: TransportType,
    val supportedFormats: List<DeviceResponseFormat>,
    val engagementPayload: ByteArray? = null,
    val sessionTranscript: ByteArray? = null,
    val nonce: ByteArray? = null,
)

/** Kiosk supported transport types. */
enum class TransportType { QR, NFC }

/** Factory that selects the best available transport for the current session. */
class TransportFactory @javax.inject.Inject constructor(
    private val nfcAdapterProvider: NfcAdapterProvider,
) {
    fun create(preferred: TransportType, engagement: DeviceEngagement): Transport {
        val ordered = orderedCandidates(preferred)
        for (candidate in ordered) {
            val descriptor = when (candidate) {
                TransportType.QR -> engagement.qr
                TransportType.NFC -> engagement.nfc
            }
            if (descriptor != null) {
                return when (candidate) {
                    TransportType.QR -> QrTransport.fromDescriptor(descriptor)
                    TransportType.NFC -> NfcTransport.fromDescriptor(descriptor) { nfcAdapterProvider.get() }
                }
            }
        }
        throw TransportException.Unsupported("Device engagement did not advertise a supported transport")
    }

    private fun orderedCandidates(preferred: TransportType): List<TransportType> = when (preferred) {
        TransportType.QR -> listOf(TransportType.QR, TransportType.NFC)
        TransportType.NFC -> listOf(TransportType.NFC, TransportType.QR)
    }
}
