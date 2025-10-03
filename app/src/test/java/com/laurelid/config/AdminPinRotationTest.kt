package com.laurelid.config
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock

class AdminPinRotationTest {
    @Test fun rotationDueWhenNeverSet() {
        val mem = object : AdminPinStorage {
            override var pinHash: String? = null
            override var pinSalt: String? = null
            override var failedAttempts: Int = 0
            override var lockoutUntilEpochMillis: Long = 0
            override var lastRotationEpochMillis: Long = 0
        }
        val mgr = AdminPinManager(mem, Clock.systemUTC())
        assertTrue(mgr.needsRotation())
    }
}
