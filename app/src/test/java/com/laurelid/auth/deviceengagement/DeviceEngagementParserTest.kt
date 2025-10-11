package com.laurelid.auth.deviceengagement

import com.laurelid.auth.DeviceResponseFormat
import com.laurelid.auth.MdocParseException
import com.upokecenter.cbor.CBORObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceEngagementParserTest {

    private val parser = DeviceEngagementParser()

    @Test
    fun `parse extracts web and nfc descriptors`() {
        val engagementBytes = CBORObject.NewMap().apply {
            set(CBORObject.FromObject("version"), CBORObject.FromObject(1))
            set(CBORObject.FromObject("retrievalMethods"), CBORObject.NewArray().apply {
                Add(webDescriptor())
                Add(nfcDescriptor())
            })
        }.EncodeToBytes()
        val payload = "mdoc://engagement?e=" + java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(engagementBytes)

        val engagement = parser.parse(payload)

        assertEquals(1, engagement.version)
        val web = requireNotNull(engagement.web)
        assertEquals(TransportType.WEB, web.type)
        assertTrue(DeviceResponseFormat.COSE_SIGN1 in web.supportedFormats)
        val nfc = requireNotNull(engagement.nfc)
        assertEquals(TransportType.NFC, nfc.type)
        assertTrue(DeviceResponseFormat.COSE_SIGN1 in nfc.supportedFormats)
    }

    @Test
    fun `parse rejects invalid scheme`() {
        assertFailsWith<MdocParseException> {
            parser.parse("https://example.invalid?e=abc")
        }
    }

    private fun webDescriptor(): CBORObject = CBORObject.NewMap().apply {
        set(CBORObject.FromObject("type"), CBORObject.FromObject("web"))
        set(
            CBORObject.FromObject("responses"),
            CBORObject.NewMap().apply {
                set(CBORObject.FromObject("cose-sign1"), CBORObject.FromObject(byteArrayOf(0x01)))
            }
        )
    }

    private fun nfcDescriptor(): CBORObject = CBORObject.NewMap().apply {
        set(CBORObject.FromObject("type"), CBORObject.FromObject("nfc"))
        set(
            CBORObject.FromObject("responses"),
            CBORObject.NewMap().apply {
                set(CBORObject.FromObject("cose-sign1"), CBORObject.FromObject(byteArrayOf(0x02)))
            }
        )
    }
}
