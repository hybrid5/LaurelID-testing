package com.laurelid.auth.deviceengagement

import com.laurelid.auth.DeviceResponseFormat
import com.laurelid.auth.MdocError
import com.laurelid.auth.MdocParseException
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceEngagementParser @Inject constructor() {

    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    fun parse(payload: String): DeviceEngagement {
        val uri = parseUri(payload)
        val queryParams = parseQuery(uri.rawQuery)
        val encodedEngagement = findDeviceEngagementParameter(queryParams)
            ?: throw MdocParseException(MdocError.MissingDeviceEngagement("QR payload did not contain a device engagement"))

        val deviceEngagementBytes = decodeBase64Url(encodedEngagement)
        val cbor = decodeCbor(deviceEngagementBytes)
        val version = extractVersion(cbor)
        val retrievalMethods = extractRetrievalMethods(cbor)
        val descriptors = retrievalMethods.mapNotNull { parseTransportDescriptor(it) }

        val web = descriptors.firstOrNull { it.type == TransportType.WEB }
        val nfc = descriptors.firstOrNull { it.type == TransportType.NFC }
        val ble = descriptors.firstOrNull { it.type == TransportType.BLE }

        if (web == null && nfc == null && ble == null) {
            throw MdocParseException(
                MdocError.UnsupportedTransport("Device engagement did not advertise a supported handover")
            )
        }
        return DeviceEngagement(version = version, web = web, nfc = nfc, ble = ble)
    }

    private fun parseUri(payload: String): URI {
        val trimmed = payload.trim()
        val uri = try {
            URI(trimmed)
        } catch (error: IllegalArgumentException) {
            throw MdocParseException(MdocError.InvalidUri("Invalid mdoc URI"), error)
        }

        val scheme = uri.scheme?.lowercase(LocaleRoot)
        if (scheme != "mdoc") {
            val schemeValue = uri.scheme ?: ""
            throw MdocParseException(MdocError.InvalidUri("Unsupported URI scheme: $schemeValue"))
        }

        val host = uri.host?.lowercase(LocaleRoot)
        val schemeSpecific = uri.schemeSpecificPart?.lowercase(LocaleRoot) ?: ""
        val hasEngagementHost = host == "engagement"
        val hasEngagementPath = schemeSpecific.startsWith("engagement") ||
                schemeSpecific.startsWith("//engagement")
        if (!hasEngagementHost && !hasEngagementPath) {
            throw MdocParseException(
                MdocError.InvalidUri("Unsupported mdoc authority: ${uri.host ?: uri.schemeSpecificPart}")
            )
        }
        return uri
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { component ->
            if (component.isBlank()) null else {
                val parts = component.split('=', limit = 2)
                val key = try {
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
                } catch (error: IllegalArgumentException) {
                    throw MdocParseException(
                        MdocError.InvalidUri("Device engagement query parameter name was not valid encoding"),
                        error
                    )
                }
                val value = if (parts.size > 1) {
                    try {
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                    } catch (error: IllegalArgumentException) {
                        throw MdocParseException(
                            MdocError.InvalidUri("Device engagement query parameter value was not valid encoding"),
                            error
                        )
                    }
                } else ""
                key.lowercase(LocaleRoot) to value
            }
        }.toMap()
    }

    private fun findDeviceEngagementParameter(params: Map<String, String>): String? =
        ENGAGEMENT_PARAM_KEYS.firstNotNullOfOrNull { key -> params[key] }

    private fun decodeBase64Url(value: String): ByteArray = try {
        urlDecoder.decode(value)
    } catch (error: IllegalArgumentException) {
        throw MdocParseException(
            MdocError.MalformedDeviceEngagement("Device engagement payload was not valid base64url"),
            error
        )
    }

    private fun decodeCbor(bytes: ByteArray): CBORObject = try {
        CBORObject.DecodeFromBytes(bytes)
    } catch (error: Exception) {
        throw MdocParseException(
            MdocError.MalformedDeviceEngagement("Device engagement was not valid CBOR"),
            error
        )
    }

    private fun extractVersion(cbor: CBORObject): Int {
        val versionCandidate = cbor.getOptional("version") ?: cbor.getOptional(0)
        return versionCandidate?.AsInt32Value() ?: 1
    }

    private fun extractRetrievalMethods(cbor: CBORObject): List<CBORObject> {
        val retrieval = cbor.getOptional("retrievalMethods") ?: cbor.getOptional(2)
        ?: throw MdocParseException(
            MdocError.MalformedDeviceEngagement("Device engagement did not contain retrieval methods")
        )
        if (retrieval.getType() != CBORType.Array) {
            throw MdocParseException(
                MdocError.MalformedDeviceEngagement("Device engagement retrieval methods must be an array")
            )
        }
        val out = mutableListOf<CBORObject>()
        for (i in 0 until retrieval.size()) out += retrieval[i]
        return out
    }

    private fun parseTransportDescriptor(method: CBORObject): TransportDescriptor? {
        if (method.getType() != CBORType.Map) return null

        val typeCandidate = method.getOptional("type") ?: method.getOptional(0)
        val type = parseTransportType(typeCandidate)
            ?: throw MdocParseException(
                MdocError.UnsupportedTransport("Encountered unsupported transport type in device engagement")
            )

        val handover = method.getOptional("handshake") ?: method.getOptional(1)
        val responses = method.getOptional("responses") ?: method.getOptional(2)
        if (responses == null || responses.getType() != CBORType.Map) {
            throw MdocParseException(
                MdocError.MalformedDeviceEngagement("Transport descriptor did not advertise any device responses")
            )
        }

        val supportedFormats = extractSupportedFormats(handover, responses)
        val responseMap = buildResponseMap(responses)

        if (supportedFormats.isEmpty() && responseMap.isEmpty()) {
            throw MdocParseException(
                MdocError.MalformedDeviceEngagement("Transport descriptor did not contain usable response encodings")
            )
        }

        val sessionTranscript = handover?.let { encodeSessionTranscript(it) }

        return TransportDescriptor(
            type = type,
            supportedFormats = if (supportedFormats.isEmpty()) responseMap.keys.toList() else supportedFormats,
            responses = responseMap,
            sessionTranscript = sessionTranscript
        )
    }

    private fun parseTransportType(candidate: CBORObject?): TransportType? {
        if (candidate == null) return null
        return when (candidate.getType()) {
            CBORType.TextString -> when (candidate.AsString().lowercase(LocaleRoot)) {
                "web", "http", "https" -> TransportType.WEB
                "nfc" -> TransportType.NFC
                "ble", "bluetooth" -> TransportType.BLE
                else -> null
            }
            CBORType.Integer -> when (candidate.AsInt32Value()) {
                2 -> TransportType.WEB
                0 -> TransportType.NFC
                1 -> TransportType.BLE
                else -> null
            }
            else -> null
        }
    }

    private fun extractSupportedFormats(handover: CBORObject?, responses: CBORObject): List<DeviceResponseFormat> {
        val formats = mutableListOf<DeviceResponseFormat>()

        val handoverMap = when {
            handover == null -> null
            handover.getType() == CBORType.Map -> handover
            handover.getType() == CBORType.ByteString -> try {
                CBORObject.DecodeFromBytes(handover.GetByteString())
            } catch (_: Exception) {
                null
            }
            else -> null
        }

        val formatsArray = handoverMap?.getOptional("formats")
            ?: handoverMap?.getOptional("supportedFormats")
            ?: handoverMap?.getOptional(0)

        if (formatsArray != null && formatsArray.getType() == CBORType.Array) {
            for (i in 0 until formatsArray.size()) {
                parseFormat(formatsArray[i])?.let { formats += it }
            }
        }

        if (formats.isEmpty()) {
            for (key in responses.getKeys()) {
                parseFormat(key)?.let { formats += it }
            }
        }
        return formats.distinct()
    }

    private fun buildResponseMap(responses: CBORObject): Map<DeviceResponseFormat, ByteArray> {
        val result = mutableMapOf<DeviceResponseFormat, ByteArray>()
        for (key in responses.getKeys()) {
            val format = parseFormat(key) ?: continue
            val value = responses[key]
            val bytes = when (value.getType()) {
                CBORType.ByteString -> value.GetByteString()
                CBORType.TextString -> value.AsString().toByteArray(StandardCharsets.UTF_8)
                else -> value.EncodeToBytes()
            }
            result[format] = bytes
        }
        return result
    }

    private fun parseFormat(entry: CBORObject): DeviceResponseFormat? = when (entry.getType()) {
        CBORType.TextString -> DeviceResponseFormat.fromLabel(entry.AsString())
        CBORType.Integer -> DeviceResponseFormat.fromLabel(entry.AsInt32Value().toString())
        else -> null
    }

    private fun encodeSessionTranscript(handover: CBORObject): ByteArray? = when (handover.getType()) {
        CBORType.ByteString -> handover.GetByteString()
        CBORType.Map, CBORType.Array -> handover.EncodeToBytes()
        else -> null
    }

    // ---- CBOR helpers ----
    private fun CBORObject.getOptional(key: String): CBORObject? =
        getOrNull(CBORObject.FromObject(key))

    private fun CBORObject.getOptional(key: Int): CBORObject? =
        getOrNull(CBORObject.FromObject(key))

    private fun CBORObject.getOrNull(key: CBORObject): CBORObject? =
        if (this.ContainsKey(key)) this[key] else null

    private companion object {
        private val LocaleRoot = java.util.Locale.ROOT
        private val ENGAGEMENT_PARAM_KEYS = listOf("e", "deviceengagement", "de", "mdoc")
    }
}
