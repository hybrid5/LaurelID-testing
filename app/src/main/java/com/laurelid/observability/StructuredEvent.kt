package com.laurelid.observability

/**
 * Immutable representation of a structured telemetry event.
 * The schema intentionally excludes subject-identifying attributes
 * to ensure the payload remains free of PII.
 */
data class StructuredEvent(
    val event: String,
    val timestampMs: Long,
    val scanDurationMs: Long? = null,
    val success: Boolean? = null,
    val reasonCode: String? = null,
    val trustStale: Boolean? = null,
)
