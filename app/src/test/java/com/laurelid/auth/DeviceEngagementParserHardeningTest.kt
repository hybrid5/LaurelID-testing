package com.laurelid.auth
import com.laurelid.auth.deviceengagement.DeviceEngagementParser
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceEngagementParserHardeningTest {
    @Test fun parseValidMdoc() {
        val r = DeviceEngagementParser.parseMdocUri("mdoc://engagement?nonce=abc&x=1")
        assertEquals("abc", r.params["nonce"])
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectBadScheme() {
        DeviceEngagementParser.parseMdocUri("http://engagement?x=1")
    }
}
