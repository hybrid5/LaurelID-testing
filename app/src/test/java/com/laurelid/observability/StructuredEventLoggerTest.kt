package com.laurelid.observability

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StructuredEventLoggerTest {
    private lateinit var exporter: InMemoryStructuredEventExporter

    @BeforeTest
    fun setUp() {
        exporter = InMemoryStructuredEventExporter()
        StructuredEventLogger.registerExporter(exporter)
    }

    @AfterTest
    fun tearDown() {
        StructuredEventLogger.registerExporter(null)
    }

    @Test
    fun `logs event with all fields`() {
        val timestamp = 172_000L
        StructuredEventLogger.log(
            event = "trust_list_refresh",
            timestampMs = timestamp,
            durationMs = 1200L,
            success = true,
            reasonCode = "OK"
        )

        val events = exporter.snapshot()
        assertEquals(1, events.size)
        val recorded = events.first()
        assertEquals("trust_list_refresh", recorded.event)
        assertEquals(timestamp, recorded.timestampMs)
        assertEquals(1200L, recorded.durationMs)
        assertEquals(true, recorded.success)
        assertEquals("OK", recorded.reasonCode)
    }

    @Test
    fun `logs event with optional fields omitted`() {
        StructuredEventLogger.log(
            event = "verification_started",
            timestampMs = 999L
        )

        val events = exporter.snapshot()
        assertEquals(1, events.size)
        val recorded = events.first()
        assertEquals("verification_started", recorded.event)
        assertEquals(999L, recorded.timestampMs)
        assertNull(recorded.durationMs)
        assertNull(recorded.success)
        assertNull(recorded.reasonCode)
    }
}
