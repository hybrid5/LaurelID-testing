package com.laurelid.config

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.laurelid.network.TrustListEndpointPolicy
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

class ConfigManager(
    private val storage: AdminConfigStorage,
) {

    constructor(context: Context) : this(EncryptedAdminConfigStorage(context))

    fun getConfig(): AdminConfig {
        val stored = storage.loadConfig()
        val refreshMinutes = sanitizeInterval(stored?.trustRefreshIntervalMinutes ?: AdminConfig.DEFAULT_TRUST_REFRESH_MINUTES)
        val endpoint = TrustListEndpointPolicy.normalizeOverrideOrNull(
            stored?.apiEndpointOverride,
            TrustListEndpointPolicy.allowOverride,
        ).orEmpty()
        return AdminConfig(
            venueId = stored?.venueId.orEmpty(),
            trustRefreshIntervalMinutes = refreshMinutes,
            apiEndpointOverride = endpoint,
            demoMode = stored?.demoMode ?: false,
        )
    }

    fun saveConfig(config: AdminConfig) {
        val sanitizedEndpoint = TrustListEndpointPolicy.normalizeOverrideOrNull(
            config.apiEndpointOverride,
            TrustListEndpointPolicy.allowOverride,
        ).orEmpty()
        val sanitized = config.copy(
            trustRefreshIntervalMinutes = sanitizeInterval(config.trustRefreshIntervalMinutes),
            apiEndpointOverride = sanitizedEndpoint,
        )
        storage.persistConfig(sanitized)
    }

    private fun sanitizeInterval(minutes: Int): Int {
        return max(1, minutes)
    }
}

interface AdminConfigStorage {
    fun loadConfig(): AdminConfig?
    fun persistConfig(config: AdminConfig)
}

class EncryptedAdminConfigStorage(context: Context) : AdminConfigStorage {

    private val appContext = context.applicationContext
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val hmacKey: SecretKey by lazy { getOrCreateHmacKey() }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        migrateLegacyPreferences()
    }

    override fun loadConfig(): AdminConfig? {
        if (!prefs.contains(KEY_HMAC)) {
            return null
        }
        val venueId = prefs.getString(KEY_VENUE_ID, "").orEmpty()
        val refreshMinutes = prefs.getInt(KEY_REFRESH_MINUTES, AdminConfig.DEFAULT_TRUST_REFRESH_MINUTES)
        val endpoint = prefs.getString(KEY_API_ENDPOINT, "").orEmpty()
        val demoMode = prefs.getBoolean(KEY_DEMO_MODE, false)
        val storedHmac = prefs.getString(KEY_HMAC, null) ?: return null
        val computed = computeHmac(venueId, refreshMinutes, endpoint, demoMode)
        return if (constantTimeEquals(storedHmac, computed)) {
            AdminConfig(
                venueId = venueId,
                trustRefreshIntervalMinutes = refreshMinutes,
                apiEndpointOverride = endpoint,
                demoMode = demoMode,
            )
        } else {
            prefs.edit { clear() }
            null
        }
    }

    override fun persistConfig(config: AdminConfig) {
        val hmac = computeHmac(
            config.venueId,
            config.trustRefreshIntervalMinutes,
            config.apiEndpointOverride,
            config.demoMode,
        )
        prefs.edit {
            putString(KEY_VENUE_ID, config.venueId)
            putInt(KEY_REFRESH_MINUTES, config.trustRefreshIntervalMinutes)
            putString(KEY_API_ENDPOINT, config.apiEndpointOverride)
            putBoolean(KEY_DEMO_MODE, config.demoMode)
            putString(KEY_HMAC, hmac)
        }
    }

    private fun migrateLegacyPreferences() {
        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_HMAC)) {
            legacyPrefs.edit { clear() }
            return
        }
        if (legacyPrefs.all.isEmpty()) {
            return
        }
        val legacyConfig = AdminConfig(
            venueId = legacyPrefs.getString(KEY_VENUE_ID, "").orEmpty(),
            trustRefreshIntervalMinutes = legacyPrefs.getInt(
                KEY_REFRESH_MINUTES,
                AdminConfig.DEFAULT_TRUST_REFRESH_MINUTES,
            ),
            apiEndpointOverride = legacyPrefs.getString(KEY_API_ENDPOINT, "").orEmpty(),
            demoMode = legacyPrefs.getBoolean(KEY_DEMO_MODE, false),
        )
        persistConfig(legacyConfig)
        legacyPrefs.edit { clear() }
    }

    private fun computeHmac(
        venueId: String,
        refreshMinutes: Int,
        apiEndpointOverride: String,
        demoMode: Boolean,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(hmacKey)
        mac.update(venueId.toByteArray(StandardCharsets.UTF_8))
        mac.update(SEPARATOR)
        mac.update(refreshMinutes.toString().toByteArray(StandardCharsets.UTF_8))
        mac.update(SEPARATOR)
        mac.update(apiEndpointOverride.toByteArray(StandardCharsets.UTF_8))
        mac.update(SEPARATOR)
        mac.update(if (demoMode) ONE else ZERO)
        return Base64.getEncoder().encodeToString(mac.doFinal())
    }

    private fun constantTimeEquals(first: String, second: String): Boolean {
        val firstBytes = first.toByteArray(StandardCharsets.UTF_8)
        val secondBytes = second.toByteArray(StandardCharsets.UTF_8)
        if (firstBytes.size != secondBytes.size) {
            return false
        }
        var diff = 0
        for (index in firstBytes.indices) {
            diff = diff or (firstBytes[index].toInt() xor secondBytes[index].toInt())
        }
        return diff == 0
    }

    private fun getOrCreateHmacKey(): SecretKey {
        return runCatching { getOrCreateKeystoreKey() }
            .getOrElse { getOrCreatePreferencesBackedKey() }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getEntry(HMAC_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            HMAC_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setKeySize(256)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getOrCreatePreferencesBackedKey(): SecretKey {
        val stored = prefs.getString(KEY_HMAC_FALLBACK, null)
        if (stored != null) {
            val decoded = Base64.getDecoder().decode(stored)
            return SecretKeySpec(decoded, HMAC_ALGORITHM)
        }
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val encoded = Base64.getEncoder().encodeToString(seed)
        prefs.edit { putString(KEY_HMAC_FALLBACK, encoded) }
        return SecretKeySpec(seed, HMAC_ALGORITHM)
    }

    companion object {
        internal const val LEGACY_PREFS_NAME = "laurelid_admin_config"
        internal const val ENCRYPTED_PREFS_NAME = "laurelid_admin_config_secure"
        internal const val KEY_VENUE_ID = "venue_id"
        internal const val KEY_REFRESH_MINUTES = "trust_refresh_minutes"
        internal const val KEY_API_ENDPOINT = "api_endpoint"
        internal const val KEY_DEMO_MODE = "demo_mode"
        internal const val KEY_HMAC = "config_hmac"
        private const val KEY_HMAC_FALLBACK = "config_hmac_key"
        private const val HMAC_KEY_ALIAS = "laurelid_admin_config_hmac"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private val SEPARATOR = byteArrayOf(0)
        private val ONE = byteArrayOf(1)
        private val ZERO = byteArrayOf(0)
    }

    @VisibleForTesting
    internal fun rawPreferencesForTest(): SharedPreferences = prefs
}
