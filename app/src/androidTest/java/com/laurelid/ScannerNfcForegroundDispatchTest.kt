package com.laurelid

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laurelid.config.AdminConfig
import com.laurelid.config.ConfigManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScannerNfcForegroundDispatchTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        ConfigManager(context).saveConfig(AdminConfig(demoMode = false))
    }

    @Test
    fun enableForegroundDispatchConfiguresAdapter() {
        ActivityScenario.launch(TestScannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setConfigForTest(AdminConfig(demoMode = false))
                val recording = activity.installRecordingNfcAdapter()

                activity.invokeEnableForegroundDispatch()

                val pendingIntent = activity.pendingIntentForTest()
                val filters = activity.intentFiltersForTest()

                assertNotNull(pendingIntent)
                assertNotNull(filters)
                assertTrue(filters!!.isNotEmpty())
                val filter = filters[0]
                assertTrue(filter.hasAction(NfcAdapter.ACTION_NDEF_DISCOVERED))
                assertTrue(filter.hasCategory(Intent.CATEGORY_DEFAULT))

                assertEquals(activity.componentName, recording.lastEnableComponent)
                assertEquals(pendingIntent, recording.lastPendingIntent)
                val recordedFilters = recording.lastIntentFilters
                assertNotNull(recordedFilters)
                assertTrue(recordedFilters!!.isNotEmpty())
            }
        }
    }

    @Test
    fun disableForegroundDispatchReleasesAdapter() {
        ActivityScenario.launch(TestScannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setConfigForTest(AdminConfig(demoMode = false))
                val recording = activity.installRecordingNfcAdapter()

                activity.invokeEnableForegroundDispatch()
                activity.invokeDisableForegroundDispatch()

                assertEquals(activity.componentName, recording.lastDisableComponent)
            }
        }
    }
}

