package com.laurelid.observability

import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight logger that emits structured telemetry events to a pluggable exporter.
 */
object StructuredEventLogger {
    private val exporterRef: AtomicReference<IEventExporter> =
        AtomicReference(NoopEventExporter)

    fun registerExporter(exporter: IEventExporter?) {
        exporterRef.set(exporter ?: NoopEventExporter)
    }

    fun log(
        event: String,
        timestampMs: Long = System.currentTimeMillis(),
        scanDurationMs: Long? = null,
        success: Boolean? = null,
        reasonCode: String? = null,
        trustStale: Boolean? = null,
    ) {
        val structuredEvent = StructuredEvent(
            event = event,
            timestampMs = timestampMs,
            scanDurationMs = scanDurationMs,
            success = success,
            reasonCode = reasonCode,
            trustStale = trustStale,
        )
        exporterRef.get().export(structuredEvent)
    }

}
