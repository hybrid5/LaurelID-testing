package com.laurelid.config

import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminPinManagerTest {

    private lateinit var storage: FakeAdminPinStorage
    private lateinit var clock: MutableClock
    private lateinit var secureRandom: SecureRandom
    private lateinit var manager: AdminPinManager

    @BeforeTest
    fun setup() {
        storage = FakeAdminPinStorage()
        clock = MutableClock()
        secureRandom = FixedSecureRandom()
        manager = AdminPinManager(storage, clock, secureRandom)
    }

    @Test
    fun verifyPinReturnsNotSetWhenNoPinConfigured() {
        val result = manager.verifyPin("123456")
        assertTrue(result is AdminPinManager.VerificationResult.PinNotSet)
    }

    @Test
    fun successfulVerificationClearsFailureState() {
        manager.updatePin("123456")

        repeat(2) { manager.verifyPin("000000") }
        assertEquals(2, storage.failedAttempts)

        val result = manager.verifyPin("123456")
        assertTrue(result is AdminPinManager.VerificationResult.Success)
        assertEquals(0, storage.failedAttempts)
        assertEquals(0L, storage.lockoutUntilEpochMillis)
    }

    @Test
    fun lockoutTriggersAfterFiveFailuresAndBacksOff() {
        manager.updatePin("123456")

        repeat(AdminPinManager.MAX_ATTEMPTS - 1) { attempt ->
            val result = manager.verifyPin("000000")
            assertTrue(result is AdminPinManager.VerificationResult.Failure)
            val remaining = (result as AdminPinManager.VerificationResult.Failure).remainingAttempts
            assertEquals(AdminPinManager.MAX_ATTEMPTS - (attempt + 1), remaining)
        }

        val locked = manager.verifyPin("000000")
        assertTrue(locked is AdminPinManager.VerificationResult.Locked)
        assertEquals(TimeUnit.SECONDS.toMillis(30), locked.remainingMillis)

        clock.advance(TimeUnit.SECONDS.toMillis(31))
        val secondLock = manager.verifyPin("000000")
        assertTrue(secondLock is AdminPinManager.VerificationResult.Locked)
        assertEquals(TimeUnit.SECONDS.toMillis(60), secondLock.remainingMillis)

        clock.advance(TimeUnit.SECONDS.toMillis(61))
        val success = manager.verifyPin("123456")
        assertTrue(success is AdminPinManager.VerificationResult.Success)
    }

    private class FakeAdminPinStorage : AdminPinStorage {
        override var pinHash: String? = null
        override var pinSalt: String? = null
        override var failedAttempts: Int = 0
        override var lockoutUntilEpochMillis: Long = 0L
    }

    private class MutableClock(
        private var currentMillis: Long = 0L,
        private val zoneId: ZoneId = ZoneOffset.UTC
    ) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(currentMillis, zone)
        override fun instant(): Instant = Instant.ofEpochMilli(currentMillis)
        override fun millis(): Long = currentMillis
        fun advance(deltaMillis: Long) {
            currentMillis += deltaMillis
        }
    }

    private class FixedSecureRandom : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0x42)
        }
    }
}
