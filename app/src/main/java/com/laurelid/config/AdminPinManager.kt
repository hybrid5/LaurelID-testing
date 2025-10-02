package com.laurelid.config

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.max

class AdminPinManager(
    private val storage: AdminPinStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom()
) {

    sealed class VerificationResult {
        object Success : VerificationResult()
        data class Failure(val remainingAttempts: Int) : VerificationResult()
        data class Locked(val remainingMillis: Long) : VerificationResult()
        object PinNotSet : VerificationResult()
    }

    fun isPinSet(): Boolean = storage.pinHash != null && storage.pinSalt != null

    fun isPinFormatValid(pin: String): Boolean {
        if (pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) return false
        return pin.all(Char::isDigit)
    }

    fun verifyPin(pin: String): VerificationResult {
        val normalized = pin.trim()
        if (!isPinSet()) {
            return VerificationResult.PinNotSet
        }

        val now = clock.millis()
        val lockoutUntil = storage.lockoutUntilEpochMillis
        if (lockoutUntil > now) {
            return VerificationResult.Locked(lockoutUntil - now)
        }

        val saltEncoded = storage.pinSalt
        val hashStored = storage.pinHash
        if (saltEncoded.isNullOrEmpty() || hashStored.isNullOrEmpty()) {
            return VerificationResult.PinNotSet
        }

        val salt = runCatching { Base64.getDecoder().decode(saltEncoded) }.getOrNull()
            ?: return VerificationResult.PinNotSet
        val providedHash = hash(normalized, salt)

        if (hashStored == providedHash) {
            storage.failedAttempts = 0
            storage.lockoutUntilEpochMillis = 0L
            return VerificationResult.Success
        }

        val newFailures = storage.failedAttempts + 1
        storage.failedAttempts = newFailures

        val lockoutDuration = computeLockoutDuration(newFailures)
        if (lockoutDuration > 0) {
            storage.lockoutUntilEpochMillis = now + lockoutDuration
            return VerificationResult.Locked(lockoutDuration)
        }

        val remaining = max(0, MAX_ATTEMPTS - newFailures)
        return VerificationResult.Failure(remaining)
    }

    fun updatePin(newPin: String) {
        require(isPinFormatValid(newPin)) { "PIN must be numeric and ${MIN_PIN_LENGTH}-${MAX_PIN_LENGTH} digits." }
        val normalized = newPin.trim()
        val salt = ByteArray(SALT_SIZE_BYTES).also(secureRandom::nextBytes)
        val hash = hash(normalized, salt)
        storage.pinSalt = Base64.getEncoder().encodeToString(salt)
        storage.pinHash = hash
        storage.failedAttempts = 0
        storage.lockoutUntilEpochMillis = 0L
        storage.lastRotationEpochMillis = clock.millis()
    }

    fun clearPin() {
        storage.pinSalt = null
        storage.pinHash = null
        storage.failedAttempts = 0
        storage.lockoutUntilEpochMillis = 0L
        storage.lastRotationEpochMillis = 0L
    }

    private fun computeLockoutDuration(failures: Int): Long {
        if (failures < MAX_ATTEMPTS) return 0L
        val exponent = failures - MAX_ATTEMPTS
        val multiplier = 1L shl exponent.coerceAtMost(LOCKOUT_EXPONENT_MAX)
        val raw = LOCKOUT_BASE_MILLIS * multiplier
        return raw.coerceAtMost(MAX_LOCKOUT_MILLIS)
    }

    fun needsRotation(): Boolean {
        val last = storage.lastRotationEpochMillis
        if (last <= 0L) return true
        val ninetyDays = TimeUnit.DAYS.toMillis(90)
        return clock.millis() - last >= ninetyDays
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val factory = SecretKeyFactory.getInstance(PBKDF_ALGORITHM)
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF_ITERATIONS, KEY_LENGTH_BITS)
        val encoded = factory.generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(encoded)
    }

    companion object {
        const val MIN_PIN_LENGTH = 6
        const val MAX_PIN_LENGTH = 12
        const val MAX_ATTEMPTS = 5
        private const val SALT_SIZE_BYTES = 16
        private const val PBKDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF_ITERATIONS = 120_000
        private const val KEY_LENGTH_BITS = 256
        private const val LOCKOUT_EXPONENT_MAX = 10
        private val LOCKOUT_BASE_MILLIS = TimeUnit.SECONDS.toMillis(30)
        private val MAX_LOCKOUT_MILLIS = TimeUnit.MINUTES.toMillis(15)
        const val PREF_FILE_NAME = "laurelid_admin_pin"
    }
}

interface AdminPinStorage {
    var pinHash: String?
    var pinSalt: String?
    var failedAttempts: Int
    var lockoutUntilEpochMillis: Long
    var lastRotationEpochMillis: Long
}

class EncryptedAdminPinStorage(context: Context) : AdminPinStorage {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            AdminPinManager.PREF_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override var pinHash: String?
        get() = prefs.getString(KEY_PIN_HASH, null)
        set(value) {
            prefs.edit {
                if (value == null) remove(KEY_PIN_HASH) else putString(KEY_PIN_HASH, value)
            }
        }

    override var pinSalt: String?
        get() = prefs.getString(KEY_PIN_SALT, null)
        set(value) {
            prefs.edit {
                if (value == null) remove(KEY_PIN_SALT) else putString(KEY_PIN_SALT, value)
            }
        }

    override var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        set(value) {
            prefs.edit { putInt(KEY_FAILED_ATTEMPTS, value) }
        }

    override var lockoutUntilEpochMillis: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        set(value) {
            prefs.edit { putLong(KEY_LOCKOUT_UNTIL, value) }
        }

    override var lastRotationEpochMillis: Long
        get() = prefs.getLong(KEY_ROTATED_AT, 0L)
        set(value) {
            prefs.edit { putLong(KEY_ROTATED_AT, value) }
        }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_ROTATED_AT = "rotated_at"
    }
}
