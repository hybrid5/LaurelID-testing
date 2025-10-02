package com.laurelid.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigManagerTest {

    private lateinit var context: Context

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences(EncryptedAdminConfigStorage.LEGACY_PREFS_NAME)
        context.deleteSharedPreferences(EncryptedAdminConfigStorage.ENCRYPTED_PREFS_NAME)
    }

    @AfterTest
    fun tearDown() {
        context.deleteSharedPreferences(EncryptedAdminConfigStorage.LEGACY_PREFS_NAME)
        context.deleteSharedPreferences(EncryptedAdminConfigStorage.ENCRYPTED_PREFS_NAME)
    }

    @Test
    fun migratesLegacyPreferencesIntoEncryptedStorage() {
        val legacy = context.getSharedPreferences(EncryptedAdminConfigStorage.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        legacy.edit()
            .putString(EncryptedAdminConfigStorage.KEY_VENUE_ID, "venue-42")
            .putInt(EncryptedAdminConfigStorage.KEY_REFRESH_MINUTES, 15)
            .putString(EncryptedAdminConfigStorage.KEY_API_ENDPOINT, "https://override.example.com/")
            .putBoolean(EncryptedAdminConfigStorage.KEY_DEMO_MODE, true)
            .commit()

        val manager = ConfigManager(context)
        val config = manager.getConfig()

        assertEquals("venue-42", config.venueId)
        assertEquals(15, config.trustRefreshIntervalMinutes)
        assertEquals("https://override.example.com/", config.apiEndpointOverride)
        assertTrue(config.demoMode)
        assertTrue(legacy.all.isEmpty())

        val reloaded = manager.getConfig()
        assertEquals(config, reloaded)
    }

    @Test
    fun tamperingClearsEncryptedPreferences() {
        val storage = EncryptedAdminConfigStorage(context)
        val manager = ConfigManager(storage)
        val saved = AdminConfig(
            venueId = "venue-7",
            trustRefreshIntervalMinutes = 10,
            apiEndpointOverride = "https://example.org/",
            demoMode = true,
        )
        manager.saveConfig(saved)

        val prefs = storage.rawPreferencesForTest()
        prefs.edit()
            .putBoolean(EncryptedAdminConfigStorage.KEY_DEMO_MODE, false)
            .commit()

        val reloaded = manager.getConfig()
        assertEquals(AdminConfig(), reloaded)
        assertFalse(prefs.contains(EncryptedAdminConfigStorage.KEY_HMAC))
    }
}
