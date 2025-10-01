package com.laurelid.observability

/**
 * Contract for exporting structured telemetry events.
 */
fun interface IEventExporter {
    fun export(event: StructuredEvent)
}
