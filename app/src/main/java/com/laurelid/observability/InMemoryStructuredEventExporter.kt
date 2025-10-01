package com.laurelid.observability

import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe exporter that buffers events in memory. Useful for tests
 * or scenarios where telemetry is polled by another component.
 */
class InMemoryStructuredEventExporter : StructuredEventExporter {
    private val backing = CopyOnWriteArrayList<StructuredEvent>()

    override fun export(event: StructuredEvent) {
        backing += event
    }

    fun snapshot(): List<StructuredEvent> = Collections.unmodifiableList(backing)

    fun clear() {
        backing.clear()
    }
}
