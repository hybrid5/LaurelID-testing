package com.laurelid.observability

/**
 * Contract for exporting structured telemetry events.
 */
fun interface StructuredEventExporter {
    fun export(event: StructuredEvent)
}
