package com.laurelid.observability

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

class FileStructuredEventExporterTest {
    private lateinit var tempDir: File
    private lateinit var exporter: FileStructuredEventExporter

    @BeforeTest
    fun setUp() {
        tempDir = createTempDir(prefix = "telemetry")
        exporter = FileStructuredEventExporter(
            directoryProvider = { tempDir },
            isDebugBuild = { true },
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `writes structured events as newline delimited json`() {
        val event = StructuredEvent(
            event = "verification_completed",
            timestampMs = 172000L,
            scanDurationMs = 321L,
            success = true,
            reasonCode = "OK",
            trustStale = false,
        )

        exporter.export(event)

        val outputFile = File(tempDir, FileStructuredEventExporter.EVENTS_FILE)
        assertTrue(outputFile.exists(), "telemetry output should exist")

        val lines = outputFile.readLines()
        assertEquals(1, lines.size)

        val parsed = JSONObject(lines.first())
        assertEquals("verification_completed", parsed.getString("event"))
        assertEquals(172000L, parsed.getLong("timestamp_ms"))
        assertEquals(321L, parsed.getLong("scan_duration_ms"))
        assertTrue(parsed.getBoolean("success"))
        assertEquals(FileStructuredEventExporter.REDACTED_PLACEHOLDER, parsed.getString("reason_code"))
        assertEquals(false, parsed.getBoolean("trust_stale"))
    }

    @Test
    fun `respects opt out of string redaction`() {
        val event = StructuredEvent(
            event = "verification_completed",
            timestampMs = 172000L,
            reasonCode = "OK",
            redactStringPayloads = false,
        )

        exporter.export(event)

        val outputFile = File(tempDir, FileStructuredEventExporter.EVENTS_FILE)
        val parsed = JSONObject(outputFile.readLines().first())
        assertEquals("OK", parsed.getString("reason_code"))
    }

    @Test
    fun `skips export when build is not debuggable`() {
        val nonDebugExporter = FileStructuredEventExporter(
            directoryProvider = { tempDir },
            isDebugBuild = { false },
        )

        nonDebugExporter.export(
            StructuredEvent(
                event = "verification_completed",
                timestampMs = 123L,
            ),
        )

        val outputFile = File(tempDir, FileStructuredEventExporter.EVENTS_FILE)
        assertFalse(outputFile.exists(), "telemetry output should not be created")
    }
}
