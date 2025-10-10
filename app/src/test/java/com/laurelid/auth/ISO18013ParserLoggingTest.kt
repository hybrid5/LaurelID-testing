package com.laurelid.auth

import android.util.Log
import com.laurelid.BuildConfig
import com.laurelid.auth.DeviceResponseParser
import com.laurelid.auth.MdocError
import com.laurelid.auth.MdocParseException
import com.laurelid.auth.deviceengagement.DeviceEngagementParser
import com.laurelid.auth.deviceengagement.TransportFactory
import io.mockk.every
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class ISO18013ParserLoggingTest {

    @BeforeTest
    fun setUp() {
        ShadowLog.clear()
    }

    @Test
    fun `hash preview never contains raw payload`() {
        val payload = "A".repeat(80)
        val preview = ISO18013Parser.hashPreview(payload)
        assertFalse(preview.contains(payload.take(16)))
        assertTrue(preview.contains('/'))
    }

    @Test
    fun `hash preview for bytes masks contents`() {
        val payload = ByteArray(80) { it.toByte() }
        val preview = ISO18013Parser.hashPreview(payload)
        val hexSnippet = payload.take(8).joinToString(separator = "") { String.format("%02x", it) }
        assertFalse(preview.contains(hexSnippet))
        assertTrue(preview.contains('/'))
    }

    @Test
    fun `qr payload logging uses hashed preview`() {
        assumeTrue(BuildConfig.DEBUG)
        val engagementParser = mockk<DeviceEngagementParser> {
            every { parse(any()) } throws MdocParseException(MdocError.Unexpected("boom"))
        }
        val transportFactory = mockk<TransportFactory>(relaxed = true)
        val deviceResponseParser = mockk<DeviceResponseParser>(relaxed = true)
        val parser = ISO18013Parser(engagementParser, transportFactory, deviceResponseParser)

        val payload = ("payload" + "1234567890").repeat(4)
        runCatching { parser.parseFromQrPayload(payload) }

        val log = ShadowLog.getLogs().first { it.tag.contains("ISO18013Parser") && it.type == Log.DEBUG }
        assertTrue(log.msg.contains("hash="))
        assertFalse(log.msg.contains(payload.take(16)))
    }
}
