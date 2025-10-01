package com.laurelid.observability

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

class FileStructuredEventExporterTest {
    private lateinit var tempDir: File
    private lateinit var exporter: FileStructuredEventExporter

    @BeforeTest
    fun setUp() {
        tempDir = createTempDir(prefix = "telemetry")
        exporter = FileStructuredEventExporter { tempDir }
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
        assertEquals("OK", parsed.getString("reason_code"))
        assertEquals(false, parsed.getBoolean("trust_stale"))
    }
}
