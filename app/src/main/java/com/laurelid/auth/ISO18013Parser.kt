package com.laurelid.auth

import com.laurelid.auth.deviceengagement.DeviceEngagementParser
import com.laurelid.auth.deviceengagement.TransportFactory
import com.laurelid.auth.deviceengagement.TransportMessage
import com.laurelid.util.Logger
import javax.inject.Inject

/**
 * Parser for ISO 18013-5 mobile driving licence engagements.
 */

class ISO18013Parser @Inject constructor(
    private val engagementParser: DeviceEngagementParser,
    private val transportFactory: TransportFactory,
    private val deviceResponseParser: DeviceResponseParser
) {

    fun parseFromQrPayload(payload: String): ParsedMdoc {
        Logger.d(TAG, "Parsing QR payload: ${payload.take(64)}")
        return try {
            val engagement = engagementParser.parse(payload)
            val transport = transportFactory.create(engagement)
            try {
                transport.start()
                val message: TransportMessage = transport.receive()
                deviceResponseParser.parse(message)
            } finally {
                transport.stop()
            }
        } catch (error: MdocParseException) {
            Logger.e(TAG, "Failed to process device engagement", error)
            throw error
        } catch (error: Exception) {
            Logger.e(TAG, "Unexpected error while processing device engagement", error)
            throw MdocParseException(
                MdocError.Unexpected("Unexpected error while processing device engagement"),
                error
            )
        }
    }

    fun parseFromNfc(bytes: ByteArray): ParsedMdoc {
        Logger.d(TAG, "Parsing NFC payload: ${bytes.toLogString()}")
        return try {
            deviceResponseParser.parse(bytes)
        } catch (error: MdocParseException) {
            Logger.e(TAG, "Failed to parse NFC device response", error)
            throw error
        } catch (error: Exception) {
            Logger.e(TAG, "Unexpected error while parsing NFC payload", error)
            throw MdocParseException(
                MdocError.Unexpected("Unexpected error while parsing NFC payload"),
                error
            )
        }
    }

    companion object {
        private const val TAG = "ISO18013Parser"
        private const val DEFAULT_DOC_TYPE = "org.iso.18013.5.1.mDL"
        private const val DEFAULT_ISSUER = "AZ-MVD"

        private fun ByteArray.toLogString(): String {
            val asString = runCatching { String(this, Charsets.UTF_8) }.getOrNull()
            return asString?.take(64) ?: "${size} bytes"
        }
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
