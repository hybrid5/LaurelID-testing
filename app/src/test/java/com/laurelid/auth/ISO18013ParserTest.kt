package com.laurelid.auth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

class ISO18013ParserTest {

    private val encoder: Base64.Encoder = Base64.getEncoder()

    @Test
    fun `parseFromQrPayload establishes transport and decodes device response`() {
        val deviceResponse = buildDeviceResponse(subjectDid = "did:example:alice")
        val qrPayload = buildQrPayload(deviceResponse, includeNfc = true, includeBle = true)

        val parser = ISO18013Parser()
        val parsed = parser.parseFromQrPayload(qrPayload)

        assertEquals("did:example:alice", parsed.subjectDid)
        assertEquals("org.iso.18013.5.1.mDL", parsed.docType)
        assertEquals("AZ-MVD", parsed.issuer)
        assertEquals(true, parsed.ageOver21)
        assertNotNull(parsed.deviceSignedEntries)
        val namespace = parsed.deviceSignedEntries!!["org.iso.18013.5.1"]
        assertNotNull(namespace)
        val familyName = namespace?.get("family_name")
        val givenName = namespace?.get("given_name")
        assertNotNull(familyName)
        assertNotNull(givenName)
        assertArrayEquals("Doe".toByteArray(), familyName!!)
        assertArrayEquals("Alice".toByteArray(), givenName!!)
    }

    @Test
    fun `parseFromQrPayload falls back to BLE when NFC is unavailable`() {
        val deviceResponse = buildDeviceResponse(subjectDid = "did:example:bob")
        val qrPayload = buildQrPayload(deviceResponse, includeNfc = false, includeBle = true)

        val parser = ISO18013Parser()
        val parsed = parser.parseFromQrPayload(qrPayload)

        assertEquals("did:example:bob", parsed.subjectDid)
        assertEquals("org.iso.18013.5.1.mDL", parsed.docType)
        assertEquals("AZ-MVD", parsed.issuer)
    }

    @Test
    fun `parseFromNfc decodes device response directly`() {
        val deviceResponse = buildDeviceResponse(subjectDid = "did:example:charlie")
        val parser = ISO18013Parser()

        val parsed = parser.parseFromNfc(deviceResponse)

        assertEquals("did:example:charlie", parsed.subjectDid)
        assertEquals("org.iso.18013.5.1.mDL", parsed.docType)
        assertEquals("AZ-MVD", parsed.issuer)
        assertNotNull(parsed.issuerAuth)
        assertArrayEquals(
            "issuer-auth".toByteArray(),
            parsed.issuerAuth!!
        )
    }

    private fun buildQrPayload(
        deviceResponse: ByteArray,
        includeNfc: Boolean,
        includeBle: Boolean
    ): String {
        val handover = JSONObject()
        if (includeNfc) {
            handover.put("nfc", transportDescriptor(deviceResponse))
        }
        if (includeBle) {
            handover.put("ble", transportDescriptor(deviceResponse))
        }

        val json = JSONObject()
        json.put("version", 1)
        json.put("handover", handover)
        return json.toString()
    }

    private fun transportDescriptor(deviceResponse: ByteArray): JSONObject {
        val descriptor = JSONObject()
        val messages = JSONArray()
        messages.put(encoder.encodeToString(deviceResponse))
        descriptor.put("messages", messages)
        return descriptor
    }

    private fun buildDeviceResponse(subjectDid: String): ByteArray {
        val nameSpace = JSONObject()
        nameSpace.put("family_name", encoder.encodeToString("Doe".toByteArray()))
        nameSpace.put("given_name", encoder.encodeToString(subjectDid.substringAfterLast(":").capitalize().toByteArray()))

        val deviceSignedEntries = JSONObject()
        deviceSignedEntries.put("org.iso.18013.5.1", nameSpace)

        val json = JSONObject()
        json.put("subjectDid", subjectDid)
        json.put("docType", "org.iso.18013.5.1.mDL")
        json.put("issuer", "AZ-MVD")
        json.put("ageOver21", true)
        json.put("issuerAuth", encoder.encodeToString("issuer-auth".toByteArray()))
        json.put("deviceSignedEntries", deviceSignedEntries)
        return json.toString().toByteArray()
    }
}

private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
