package com.laurelid.deviceengagement

import com.laurelid.mdoc.DeviceResponseFormat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransportFactoryTest {

    private val factory = TransportFactory { null }

    @Test
    fun `prefers requested transport when available`() {
        val engagement = DeviceEngagement(
            version = 1,
            qr = descriptor(TransportType.QR),
            nfc = descriptor(TransportType.NFC),
        )

        val transport = factory.create(TransportType.QR, engagement)

        assertIs<QrTransport>(transport)
        assertTrue(transport.currentFormat() == DeviceResponseFormat.COSE_SIGN1)
    }

    @Test
    fun `falls back to alternate transport when preferred missing`() {
        val engagement = DeviceEngagement(
            version = 1,
            qr = null,
            nfc = descriptor(TransportType.NFC),
        )

        val transport = factory.create(TransportType.QR, engagement)

        assertIs<NfcTransport>(transport)
    }

    @Test
    fun `throws when no supported transport`() {
        val engagement = DeviceEngagement(version = 1, qr = null, nfc = null)

        assertFailsWith<TransportException.Unsupported> {
            factory.create(TransportType.QR, engagement)
        }
    }

    private fun descriptor(type: TransportType): TransportDescriptor = TransportDescriptor(
        type = type,
        supportedFormats = listOf(DeviceResponseFormat.COSE_SIGN1),
        engagementPayload = byteArrayOf(0x01),
        sessionTranscript = byteArrayOf(0x02),
        nonce = byteArrayOf(0x03),
    )
}
