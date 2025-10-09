package com.laurelid

import java.util.concurrent.atomic.AtomicBoolean

/** Runtime-togglable feature flags for kiosk behaviour. */
object FeatureFlags {
    private val integrityGate = AtomicBoolean(false)

    var integrityGateEnabled: Boolean
        get() = integrityGate.get()
        set(value) { integrityGate.set(value) }
}
