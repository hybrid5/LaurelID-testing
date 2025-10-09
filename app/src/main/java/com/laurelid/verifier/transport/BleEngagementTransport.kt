package com.laurelid.verifier.transport

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/** Simple stub transport for future BLE support. */
@Singleton
class BleEngagementTransport @Inject constructor() : EngagementTransport {
    override suspend fun start(): EngagementSession {
        // Placeholder implementation that waits briefly before returning a synthetic session.
        delay(100)
        return EngagementSession("ble-disabled", ByteArray(0))
    }

    override suspend fun stop() = Unit
}
