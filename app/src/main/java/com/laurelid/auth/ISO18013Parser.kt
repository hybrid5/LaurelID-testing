package com.laurelid.auth

import com.laurelid.auth.deviceengagement.DeviceEngagementParser
import com.laurelid.auth.deviceengagement.DeviceEngagementTransportFactory
import com.laurelid.auth.deviceengagement.TransportFactory
import com.laurelid.util.Logger

/**
 * Lightweight placeholder parser for ISO 18013-5 mobile driving licences.
 * The real implementation must perform the full mdoc engagement, device retrieval,
 * COSE/SD-JWT validation, and data element verification as defined by the spec.
 */
class ISO18013Parser(
    private val engagementParser: DeviceEngagementParser = DeviceEngagementParser(),
    private val transportFactory: TransportFactory = DeviceEngagementTransportFactory(),
    private val deviceResponseParser: DeviceResponseParser = DeviceResponseParser(
        defaultDocType = DEFAULT_DOC_TYPE,
        defaultIssuer = DEFAULT_ISSUER
    )
) {

    fun parseFromQrPayload(payload: String): ParsedMdoc {
        Logger.d(TAG, "Parsing QR payload: ${payload.take(64)}")
        val engagement = engagementParser.parse(payload)
        val transport = transportFactory.create(engagement)
        transport.start()
        return try {
            val sessionBytes = transport.receive()
            deviceResponseParser.parse(sessionBytes)
        } finally {
            transport.stop()
        }
    }

    fun parseFromNfc(bytes: ByteArray): ParsedMdoc {
        Logger.d(TAG, "Parsing NFC payload: ${bytes.toString(Charsets.UTF_8).take(64)}")
        return deviceResponseParser.parse(bytes)
    }

    companion object {
        private const val TAG = "ISO18013Parser"
        private const val DEFAULT_DOC_TYPE = "org.iso.18013.5.1.mDL"
        private const val DEFAULT_ISSUER = "AZ-MVD"
    }
}

data class ParsedMdoc(
    val subjectDid: String,
    val docType: String,
    val issuer: String,
    val ageOver21: Boolean,
    val issuerAuth: ByteArray? = null,
    val deviceSignedEntries: Map<String, Map<String, ByteArray>>? = null,
    val deviceSignedCose: ByteArray? = null,
)
