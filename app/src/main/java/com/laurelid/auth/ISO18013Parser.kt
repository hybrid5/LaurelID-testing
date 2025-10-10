package com.laurelid.auth

import com.laurelid.BuildConfig
import com.laurelid.auth.deviceengagement.DeviceEngagementParser
import com.laurelid.auth.deviceengagement.TransportFactory
import com.laurelid.auth.deviceengagement.TransportMessage
import com.laurelid.util.Logger
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import kotlin.text.Charsets

/**
 * Parser for ISO 18013-5 mobile driving licence engagements.
 */

class ISO18013Parser @Inject constructor(
    private val engagementParser: DeviceEngagementParser,
    private val transportFactory: TransportFactory,
    private val deviceResponseParser: DeviceResponseParser
) {

    fun parseFromQrPayload(payload: String): ParsedMdoc {
        if (BuildConfig.DEBUG) {
            Logger.d(TAG, "Parsing QR payload hash=${hashPreview(payload)}")
        }
        return try {
            val engagement = engagementParser.parse(payload)
            val transport = transportFactory.create(engagement)
            try {
                val message: TransportMessage = runBlocking {
                    transport.start()
                    transport.receive()
                }
                deviceResponseParser.parse(message)
            } finally {
                runBlocking { transport.stop() }
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
        if (BuildConfig.DEBUG) {
            Logger.d(TAG, "Parsing NFC payload hash=${hashPreview(bytes)}")
        }
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

        internal fun hashPreview(payload: String): String {
            if (payload.isEmpty()) return "empty/0"
            val preview = payload.take(64)
            return hashBytes(preview.toByteArray(Charsets.UTF_8), preview.length, payload.length)
        }

        internal fun hashPreview(payload: ByteArray): String {
            if (payload.isEmpty()) return "empty/0"
            val preview = if (payload.size <= 64) payload else payload.copyOfRange(0, 64)
            return hashBytes(preview, preview.size, payload.size)
        }

        private fun hashBytes(bytes: ByteArray, previewLength: Int, totalLength: Int): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.joinToString(separator = "") { byte ->
                "%02x".format(byte)
            }
            return "$hex/$previewLength:$totalLength"
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
