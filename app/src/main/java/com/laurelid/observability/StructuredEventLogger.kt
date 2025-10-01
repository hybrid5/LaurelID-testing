package com.laurelid.observability

import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight logger that emits structured telemetry events to a pluggable exporter.
 */
object StructuredEventLogger {
    private val exporterRef: AtomicReference<StructuredEventExporter> =
        AtomicReference(NoopStructuredEventExporter)

    fun registerExporter(exporter: StructuredEventExporter?) {
        exporterRef.set(exporter ?: NoopStructuredEventExporter)
    }

    fun log(
        event: String,
        timestampMs: Long = System.currentTimeMillis(),
        durationMs: Long? = null,
        success: Boolean? = null,
        reasonCode: String? = null,
    ) {
        val structuredEvent = StructuredEvent(
            event = event,
            timestampMs = timestampMs,
            durationMs = durationMs,
            success = success,
            reasonCode = reasonCode,
        )
        exporterRef.get().export(structuredEvent)
    }

    private object NoopStructuredEventExporter : StructuredEventExporter {
        override fun export(event: StructuredEvent) {
            // Intentionally empty
        }
    }
}
