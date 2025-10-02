package com.laurelid.auth

import com.augustcellars.cose.CoseException
import com.augustcellars.cose.Sign1Message
import com.laurelid.auth.deviceengagement.TransportMessage
import com.laurelid.util.Logger
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class DeviceResponseParser(
    private val defaultDocType: String,
    private val defaultIssuer: String
) {

    private val base64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    fun parse(message: TransportMessage): ParsedMdoc {
        return parse(message.payload, message.format)
    }

    fun parse(deviceResponse: ByteArray): ParsedMdoc {
        return parse(deviceResponse, null)
    }

    private fun parse(bytes: ByteArray, formatHint: DeviceResponseFormat?): ParsedMdoc {
        val format = formatHint ?: detectFormat(bytes)
        return when (format) {
            DeviceResponseFormat.COSE_SIGN1 -> parseCose(bytes)
            DeviceResponseFormat.SD_JWT -> parseSdJwt(bytes)
        }
    }

    private fun detectFormat(bytes: ByteArray): DeviceResponseFormat {
        return if (looksLikeSdJwt(bytes)) {
            DeviceResponseFormat.SD_JWT
        } else {
            DeviceResponseFormat.COSE_SIGN1
        }
    }

    private fun looksLikeSdJwt(bytes: ByteArray): Boolean {
        val text = runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull() ?: return false
        return text.contains('.') && text.contains('~')
    }

    private fun parseCose(bytes: ByteArray): ParsedMdoc {
        val message = try {
            Sign1Message.DecodeFromBytes(bytes)
        } catch (error: CoseException) {
            Logger.e(TAG, "Failed to decode COSE message", error)
            throw MdocParseException(
                MdocError.MalformedDeviceResponse("Device response was not a valid COSE_Sign1 structure"),
                error
            )
        }
        val payload = message.content ?: throw MdocParseException(
            MdocError.MalformedDeviceResponse("COSE device response did not contain a payload")
        )
        val cbor = try {
            CBORObject.DecodeFromBytes(payload)
        } catch (error: Exception) {
            Logger.e(TAG, "Failed to decode COSE payload as CBOR", error)
            throw MdocParseException(MdocError.MalformedDeviceResponse("COSE payload was not valid CBOR"), error)
        }
        val documents = cbor.getOptional("documents") ?: cbor.getOptional(0)
            ?: throw MdocParseException(
                MdocError.MalformedDeviceResponse("COSE payload did not contain any documents")
            )
        if (!documents.isArray || documents.size() == 0) {
            throw MdocParseException(
                MdocError.MalformedDeviceResponse("Device response documents array was empty")
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
        } catch (error: Exception) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload was not valid UTF-8"), error)
        }
        if (token.isBlank()) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload was empty"))
        }
        val segments = token.split('~')
        if (segments.isEmpty()) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload was malformed"))
        }
        val jwt = segments.first()
        val disclosures = segments.drop(1)
        val jwtParts = jwt.split('.')
        if (jwtParts.size < 2) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT core JWT section was malformed"))
        }
        val payloadJson = try {
            val decoded = base64UrlDecoder.decode(jwtParts[1])
            String(decoded, StandardCharsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload segment was not valid base64"), error)
        }
        val payload = try {
            JSONObject(payloadJson)
        } catch (error: JSONException) {
            throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT payload was not valid JSON"), error)
        }
        val disclosureClaims = mutableMapOf<String, Any?>()
        disclosures.forEach { disclosure ->
            if (disclosure.isBlank()) return@forEach
            val disclosureJson = try {
                val decoded = base64UrlDecoder.decode(disclosure)
                String(decoded, StandardCharsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT disclosure was not valid base64"), error)
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
            } catch (error: JSONException) {
                throw MdocParseException(MdocError.MalformedDeviceResponse("SD-JWT disclosure was not valid JSON"), error)
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
            is String -> value.equals("true", ignoreCase = true)
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
        val namespaceIterator = nameSpaces.keys
        while (namespaceIterator.hasNext()) {
            val namespaceKey = namespaceIterator.next()
            val namespace = when (namespaceKey.type) {
                CBORType.TextString -> namespaceKey.AsString()
                CBORType.ByteString -> String(namespaceKey.GetByteString(), StandardCharsets.UTF_8)
                else -> continue
            }
            val entries = mutableMapOf<String, ByteArray>()
            val entryObject = nameSpaces.get(namespaceKey)
            if (!entryObject.isMap) continue
            val entryIterator = entryObject.keys
            while (entryIterator.hasNext()) {
                val entryKey = entryIterator.next()
                val entryName = when (entryKey.type) {
                    CBORType.TextString -> entryKey.AsString()
                    CBORType.ByteString -> String(entryKey.GetByteString(), StandardCharsets.UTF_8)
                    else -> continue
                }
                val entryValue = entryObject.get(entryKey)
                when {
                    entryName.equals("subject_did", ignoreCase = true) && entryValue.type == CBORType.TextString ->
                        subjectDid = entryValue.AsString()
                    entryName.equals("age_over_21", ignoreCase = true) && entryValue.type == CBORType.Boolean ->
                        ageOver21 = entryValue.AsBoolean()
                }
                entries[entryName] = entryValue.EncodeToBytes()
            }
            if (entries.isNotEmpty()) {
                result[namespace] = entries
            }
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

    private fun CBORObject.getOptional(key: String): CBORObject? = getOrNull(CBORObject.FromObject(key))

    private fun CBORObject.getOptional(key: Int): CBORObject? = getOrNull(CBORObject.FromObject(key))

    private fun CBORObject.getOptionalString(key: String): String? = getOptional(key)?.takeIf { it.isTextString }?.AsString()

    private fun CBORObject.getOptionalBytes(key: String): ByteArray? = getOptional(key)?.takeIf { it.isByteString }?.GetByteString()

    private fun CBORObject.getOrNull(key: CBORObject): CBORObject? = if (containsKey(key)) get(key) else null

    private val Sign1Message.content: ByteArray?
        get() = try {
            GetContent()
        } catch (error: CoseException) {
            Logger.e(TAG, "Unable to extract COSE content", error)
            throw MdocParseException(MdocError.MalformedDeviceResponse("Failed to extract COSE payload"), error)
        }

    private data class ParsedNameSpaces(
        val entries: Map<String, Map<String, ByteArray>> = emptyMap(),
        val subjectDid: String? = null,
        val ageOver21: Boolean = false,
    )

    companion object {
        private const val TAG = "DeviceResponseParser"
        private const val DEFAULT_NAMESPACE = "org.iso.18013.5.1"
    }
}
