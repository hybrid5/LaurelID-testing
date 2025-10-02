package com.laurelid.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laurelid.config.AdminConfig
import com.laurelid.data.VerificationResult
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogManagerTest {
    private lateinit var context: Context
    private lateinit var clock: Clock

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
        context.getSharedPreferences("log_migrations", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "logs").deleteRecursively()
    }

    @Test
    fun writesEncryptedLogs() {
        val logManager = object : LogManager(context, clock) {
            override val maxLogBytes: Long = Long.MAX_VALUE
        }
        logManager.purgeLegacyLogs()
        logManager.appendVerification(buildResult(), buildConfig(), demoModeUsed = false)

        val file = File(File(context.filesDir, "logs"), "verify.log")
        assertTrue(file.exists())
        val rawContent = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(rawContent.contains("venue-123"))

        val decrypted = logManager.readEncryptedLines(file)
        assertEquals(1, decrypted.size)
        val parsed = JSONObject(decrypted.first())
        assertEquals("REDACTED", parsed.getString("venueId"))
        assertTrue(parsed.isNull("success"))
        assertTrue(parsed.isNull("ageOver21"))
        assertTrue(parsed.isNull("demoMode"))
    }

    @Test
    fun rotatesLogsWhenThresholdReached() {
        val logManager = object : LogManager(context, clock) {
            override val maxLogBytes: Long = 0L
        }
        logManager.purgeLegacyLogs()

        repeat(2) { index ->
            logManager.appendVerification(
                buildResult(subject = "did:example:$index"),
                buildConfig(),
                demoModeUsed = index % 2 == 0,
            )
        }

        val dir = File(context.filesDir, "logs")
        val active = File(dir, "verify.log")
        val rotated = File(dir, "verify.log.1")
        assertTrue(rotated.exists(), "Rotated log should exist")
        assertTrue(active.exists(), "Active log should exist after rotation")

        val activeDecrypted = logManager.readEncryptedLines(active)
        val rotatedDecrypted = logManager.readEncryptedLines(rotated)
        assertEquals(1, activeDecrypted.size)
        assertEquals(1, rotatedDecrypted.size)
        assertFalse(rotated.readBytes().toString(Charsets.ISO_8859_1).contains("did:example"))
    }

    private fun buildResult(subject: String? = "did:example:alice") = VerificationResult(
        success = true,
        ageOver21 = true,
        issuer = "ExampleIssuer",
        subjectDid = subject,
        docType = "org.iso.18013.5.1.mDL",
        error = null,
        trustStale = false,
    )

    private fun buildConfig() = AdminConfig(
        venueId = "venue-123",
        trustRefreshIntervalMinutes = AdminConfig.DEFAULT_TRUST_REFRESH_MINUTES,
        apiEndpointOverride = "",
        demoMode = false,
    )
}
