package com.laurelid.auth

import android.util.Base64 as AndroidBase64
import COSE.Message
import COSE.CoseException
import COSE.Sign1Message
import com.laurelid.auth.deviceengagement.TransportMessage
import com.laurelid.util.Logger
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class DeviceResponseParser(
    private val defaultDocType: String,
    private val defaultIssuer: String
) {

    fun parse(message: TransportMessage): ParsedMdoc = parse(message.payload, message.format)

    fun parse(deviceResponse: ByteArray): ParsedMdoc = parse(deviceResponse, null)

    private fun parse(bytes: ByteArray, formatHint: DeviceResponseFormat?): ParsedMdoc {
        val (payload, uriFormatHint) = decodeMdocUriIfPresent(bytes)
        val format = formatHint ?: uriFormatHint ?: detectFormat(payload)
        return when (format) {
            DeviceResponseFormat.COSE_SIGN1 -> parseCose(payload)
            DeviceResponseFormat.SD_JWT -> parseSdJwt(payload)
        }
    }

    private fun decodeMdocUriIfPresent(bytes: ByteArray): Pair<ByteArray, DeviceResponseFormat?> {
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()?.trim()
            ?: return bytes to null
        if (!text.startsWith(MDOC_URI_PREFIX, ignoreCase = true)) return bytes to null

        val uri = try {
            URI(text)
        } catch (e: IllegalArgumentException) {
            throw MdocParseException(MdocError.InvalidUri("Invalid mdoc device response URI"), e)
        }

        val params = parseQueryParameters(uri.rawQuery)
        val encodedPayload = DEVICE_RESPONSE_PARAM_KEYS.firstNotNullOfOrNull { params[it] }
            ?: throw MdocParseException(
                MdocError.MalformedDeviceResponse("mdoc URI missing device response payload")
            )

        val decodedPayload = try {
            base64UrlDecode(encodedPayload)
        } catch (e: IllegalArgumentException) {
            throw MdocParseException(
                MdocError.MalformedDeviceResponse("mdoc URI payload was not valid base64url"),
                e
            )
        }

        val formatLabel = FORMAT_PARAM_KEYS.firstNotNullOfOrNull { params[it] }
        val uriFormat = formatLabel?.let { DeviceResponseFormat.fromLabel(it) }

        return decodedPayload to uriFormat
    }

    private fun parseQueryParameters(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { component ->
            if (component.isBlank()) null
            else {
                val parts = component.split('=', limit = 2)
                val key = decodeComponent(parts[0])
                val value = if (parts.size > 1) decodeComponent(parts[1]) else ""
                key.lowercase(Locale.ROOT) to value
            }
        }.toMap()
    }

    private fun decodeComponent(component: String): String = try {
        URLDecoder.decode(component, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        component
    }

    private fun detectFormat(bytes: ByteArray): DeviceResponseFormat =
        if (looksLikeSdJwt(bytes)) DeviceResponseFormat.SD_JWT else DeviceResponseFormat.COSE_SIGN1

    private fun looksLikeSdJwt(bytes: ByteArray): Boolean {
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: return false
        return text.count { it == '.' } >= 2 && text.contains('~')
    }

    private fun parseCose(bytes: ByteArray): ParsedMdoc {
        val sign1: Sign1Message = try {
            val msg = Message.DecodeFromBytes(bytes)
            (msg as? Sign1Message) ?: throw MdocParseException(
                MdocError.MalformedDeviceResponse("COSE message is not a COSE_Sign1 structure")
            )
        } catch (e: CoseException) {
            Logger.e(TAG, "Failed to decode COSE message", e)
            throw MdocParseException(
                MdocError.MalformedDeviceResponse("Device response was not a valid COSE_Sign1 structure"), e
            )
        }

        val payload: ByteArray = try {
            sign1.GetContent()
        } catch (e: CoseException) {
            Logger.e(TAG, "Unable to extract COSE content", e)
            throw MdocParseException(MdocError.MalformedDeviceResponse("Failed to extract COSE payload"), e)
        } ?: throw MdocParseException(
            MdocError.MalformedDeviceResponse("COSE device response did not contain a payload")
        )

        val cbor = try {
            CBORObject.DecodeFromBytes(payload)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to decode COSE payload as CBOR", e)
            throw MdocParseException(MdocError.MalformedDeviceResponse("COSE payload was not valid CBOR"), e)
        }

        val documents = cbor.getOptional("documents") ?: cbor.getOptional(0)
        ?: throw MdocParseException(
            MdocError.MalformedDeviceResponse("COSE payload did not contain any documents")
        )

        if (documents.getType() != CBORType.Array || documents.size() == 0) {
            throw MdocParseException(
                MdocError.MalformedDeviceResponse("Device response documents array was empty or wrong type")
            )
        }

        val document = documents[0]
        val docType = document.getOptionalString("docType") ?: defaultDocType

        val issuerSigned = document.getOptional("issuerSigned")
        val issuer = issuerSigned?.getOptionalString("issuer") ?: defaultIssuer
        val issuerAuth = issuerSigned?.getOptionalBytes("issuerAuth")

        val deviceSigned = document.getOptional("deviceSigned")
            ?: throw MdocParseException(
                MdocError.MalformedDeviceResponse("Device response was missing deviceSigned payload")
            )

        val parsedNameSpaces = deviceSigned.getOptional("nameSpaces")?.let { parseNameSpaces(it) }
            ?: ParsedNameSpaces()

        val deviceSignedCose = deviceSigned.getOptional("deviceAuth")?.getOptionalBytes("deviceSignature")
        val subjectDid = parsedNameSpaces.subjectDid ?: fallbackSubjectDid(bytes)

        return ParsedMdoc(
            subjectDid = subjectDid,
            docType = docType,
            issuer = issuer,
            ageOver21 = parsedNameSpaces.ageOver21,
            issuerAuth = issuerAuth,
            deviceSignedEntries = parsedNameSpaces.entries.takeIf { it.isNotEmpty() },
            deviceSignedCose = deviceSignedCose,
        )
    }


    private fun parseSdJwt(bytes: ByteArray): ParsedMdoc {
        val token = try {
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload was not valid UTF-8"), e)
        }
        if (token.isBlank()) throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload was empty"))

        val segments = token.split('~')
        if (segments.isEmpty()) throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload was malformed"))

        val jwt = segments.first()
        val disclosures = segments.drop(1).filter { it.isNotBlank() }

        val jwtParts = jwt.split('.')
        if (jwtParts.size < 2) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT core JWT section was malformed"))
        }

        val payloadJson = try {
            val decoded = base64UrlDecode(jwtParts[1])
            String(decoded, StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw MdocParseException(
                MdocError.MalformedDeviceResponse("SD-JWT payload segment was not valid base64url"),
                e
            )
        }

        val payload = try {
            JSONObject(payloadJson)
        } catch (e: JSONException) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload was not valid JSON"), e)
        }

        val disclosureClaims = mutableMapOf<String, Any?>()
        disclosures.forEach { disclosure ->
            val disclosureJson = try {
                val decoded = base64UrlDecode(disclosure)
                String(decoded, StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw MdocParseException(
                    MdocError.MalformedDeviceResponse("SD-JWT disclosure was not valid base64url"),
                    e
                )
            }
            try {
                val array = JSONArray(disclosureJson)
                if (array.length() >= 3) {
                    val claimName = array.optString(1)
                    val claimValue = array.opt(2)
                    if (!claimName.isNullOrEmpty()) {
                        disclosureClaims[claimName] = claimValue
                    }
                }
            } catch (e: JSONException) {
                throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT disclosure was not valid JSON"), e)
            }
        }

        val docType = payload.optString("doc_type", defaultDocType)
        val issuer = payload.optString("iss", defaultIssuer)

        val subjectDid = (disclosureClaims["subject_did"] as? String)
            ?: payload.optString("sub", null)
            ?: fallbackSubjectDid(bytes)

        val ageOver21 = when (val value = disclosureClaims["age_over_21"]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> payload.optBoolean("age_over_21", false)
        }

        val namespaceEntries = mapOf(
            DEFAULT_NAMESPACE to disclosureClaims.mapValues { (_, value) -> encodeAsCborBytes(value) }
        )

        return ParsedMdoc(
            subjectDid = subjectDid,
            docType = docType,
            issuer = issuer,
            ageOver21 = ageOver21,
            issuerAuth = null,
            deviceSignedEntries = namespaceEntries,
            deviceSignedCose = null,
        )
    }

    private fun parseNameSpaces(nameSpaces: CBORObject): ParsedNameSpaces {
        val result = mutableMapOf<String, MutableMap<String, ByteArray>>()
        var subjectDid: String? = null
        var ageOver21 = false

        for (namespaceKey in nameSpaces.getKeys()) {
            val namespace = when (namespaceKey.getType()) {
                CBORType.TextString -> namespaceKey.AsString()
                CBORType.ByteString -> String(namespaceKey.GetByteString(), StandardCharsets.UTF_8)
                else -> continue
            }

            val entryObject = nameSpaces[namespaceKey]
            if (entryObject.getType() != CBORType.Map) continue

            val entries = mutableMapOf<String, ByteArray>()
            for (entryKey in entryObject.getKeys()) {
                val entryName = when (entryKey.getType()) {
                    CBORType.TextString -> entryKey.AsString()
                    CBORType.ByteString -> String(entryKey.GetByteString(), StandardCharsets.UTF_8)
                    else -> continue
                }
                val entryValue = entryObject[entryKey]

                when {
                    entryName.equals("subject_did", ignoreCase = true) &&
                            entryValue.getType() == CBORType.TextString ->
                        subjectDid = entryValue.AsString()
                    entryName.equals("age_over_21", ignoreCase = true) &&
                            entryValue.getType() == CBORType.Boolean ->
                        ageOver21 = entryValue.AsBoolean()
                }

                entries[entryName] = entryValue.EncodeToBytes()
            }
            if (entries.isNotEmpty()) result[namespace] = entries
        }
        return ParsedNameSpaces(entries = result, subjectDid = subjectDid, ageOver21 = ageOver21)
    }

    private fun encodeAsCborBytes(value: Any?): ByteArray {
        val cbor = when (value) {
            null -> CBORObject.Null
            is Boolean -> CBORObject.FromObject(value)
            is Number -> CBORObject.FromObject(value)
            is JSONObject, is JSONArray -> CBORObject.FromObject(value.toString())
            is ByteArray -> CBORObject.FromObject(value)
            else -> CBORObject.FromObject(value.toString())
        }
        return cbor.EncodeToBytes()
    }

    private fun fallbackSubjectDid(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "did:example:${UUID.nameUUIDFromBytes(digest)}"
    }

    // ---- CBOR helpers ----
    private fun CBORObject.getOptional(key: String): CBORObject? =
        getOrNull(CBORObject.FromObject(key))

    private fun CBORObject.getOptional(key: Int): CBORObject? =
        getOrNull(CBORObject.FromObject(key))

    private fun CBORObject.getOptionalString(key: String): String? =
        getOptional(key)?.takeIf { it.getType() == CBORType.TextString }?.AsString()

    private fun CBORObject.getOptionalBytes(key: String): ByteArray? =
        getOptional(key)?.takeIf { it.getType() == CBORType.ByteString }?.GetByteString()

    private fun CBORObject.getOrNull(key: CBORObject): CBORObject? =
        if (this.ContainsKey(key)) this[key] else null

    // ---- Base64URL (API-safe) ----
    private fun base64UrlDecode(input: String): ByteArray {
        val normalized = input
            .replace('-', '+')
            .replace('_', '/')
            .let {
                val pad = (4 - it.length % 4) % 4
                it + "=".repeat(pad)
            }
        return try {
            java.util.Base64.getDecoder().decode(normalized)
        } catch (_: Throwable) {
            AndroidBase64.decode(normalized, AndroidBase64.NO_WRAP)
        }
    }

    private data class ParsedNameSpaces(
        val entries: Map<String, Map<String, ByteArray>> = emptyMap(),
        val subjectDid: String? = null,
        val ageOver21: Boolean = false,
    )

    companion object {
        private const val TAG = "DeviceResponseParser"
        private const val DEFAULT_NAMESPACE = "org.iso.18013.5.1"
        private const val MDOC_URI_PREFIX = "mdoc://"
        private val DEVICE_RESPONSE_PARAM_KEYS = listOf("dr", "deviceResponse", "response")
        private val FORMAT_PARAM_KEYS = listOf("type", "format", "responseType")
    }
}
