package com.laurelid.observability

/**
 * Exporter that intentionally discards all telemetry.
 */
object NoopEventExporter : IEventExporter {
    override fun export(event: StructuredEvent) {
        // Intentionally empty
    }
}
