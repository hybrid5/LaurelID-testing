package com.laurelid.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.reflect.full.memberProperties

class StructuredEventSchemaTest {

    @Test
    fun `structured event exposes expected fields`() {
        val propertyNames = StructuredEvent::class.memberProperties.map { it.name }.sorted()
        val expected = listOf("durationMs", "event", "reasonCode", "success", "timestampMs")

        assertEquals(expected, propertyNames)
    }

    @Test
    fun `structured event defaults leave optional fields null`() {
        val event = StructuredEvent(
            event = "trust_list_refresh",
            timestampMs = 100L
        )

        assertEquals("trust_list_refresh", event.event)
        assertEquals(100L, event.timestampMs)
        assertTrue(event.durationMs == null && event.success == null && event.reasonCode == null)
    }
}
