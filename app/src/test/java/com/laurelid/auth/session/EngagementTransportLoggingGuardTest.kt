package com.laurelid.auth.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngagementTransportLoggingGuardTest {

    @Test
    fun `nfc engagement logging redacts payload`() {
        val source = File("src/main/java/com/laurelid/auth/session/EngagementTransport.kt").readText()
        assertTrue(source.contains("NFC engagement payloadHash="))
        assertFalse(source.contains("NFC engagement payload="))
    }
}
