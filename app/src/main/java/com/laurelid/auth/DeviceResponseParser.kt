package com.laurelid.auth

import com.laurelid.util.Logger
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class DeviceResponseParser(
    private val defaultDocType: String,
    private val defaultIssuer: String
) {

    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun parse(deviceResponse: ByteArray): ParsedMdoc {
        return try {
            val json = JSONObject(String(deviceResponse, Charsets.UTF_8))
            val subjectDid = if (json.has(SUBJECT_DID)) {
                json.getString(SUBJECT_DID)
            } else {
                fallbackSubjectDid(deviceResponse)
            }
            val docType = if (json.has(DOC_TYPE)) json.getString(DOC_TYPE) else defaultDocType
            val issuer = if (json.has(ISSUER)) json.getString(ISSUER) else defaultIssuer
            val ageOver21 = json.optBoolean(AGE_OVER_21, false)
            val issuerAuth = json.optString(ISSUER_AUTH, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { decoder.decode(it) }
            val deviceSignedEntries = json.optJSONObject(DEVICE_SIGNED_ENTRIES)?.let { parseNameSpaces(it) }

            ParsedMdoc(
                subjectDid = subjectDid,
                docType = docType,
                issuer = issuer,
                ageOver21 = ageOver21,
                issuerAuth = issuerAuth,
                deviceSignedEntries = deviceSignedEntries
            )
        } catch (jsonException: JSONException) {
            Logger.e(TAG, "Failed to parse device response", jsonException)
            throw IllegalArgumentException("Invalid device response payload", jsonException)
        }
    }

    private fun parseNameSpaces(nameSpaces: JSONObject): Map<String, Map<String, ByteArray>> {
        val result = mutableMapOf<String, Map<String, ByteArray>>()
        val namespaceIterator = nameSpaces.keys()
        while (namespaceIterator.hasNext()) {
            val namespace = namespaceIterator.next()
            val entries = mutableMapOf<String, ByteArray>()
            val entryObject = nameSpaces.getJSONObject(namespace)
            val entryIterator = entryObject.keys()
            while (entryIterator.hasNext()) {
                val entryName = entryIterator.next()
                val encodedValue = entryObject.getString(entryName)
                entries[entryName] = decoder.decode(encodedValue)
            }
            result[namespace] = entries
        }
        return result
    }

    private fun fallbackSubjectDid(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "did:example:${UUID.nameUUIDFromBytes(digest)}"
    }

    companion object {
        private const val TAG = "DeviceResponseParser"

        private const val SUBJECT_DID = "subjectDid"
        private const val DOC_TYPE = "docType"
        private const val ISSUER = "issuer"
        private const val AGE_OVER_21 = "ageOver21"
        private const val ISSUER_AUTH = "issuerAuth"
        private const val DEVICE_SIGNED_ENTRIES = "deviceSignedEntries"
    }
}
