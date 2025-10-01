package com.laurelid

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laurelid.kiosk.KioskWatchdogService
import com.laurelid.ui.ScannerActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KioskWatchdogServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Intents.init()
        context.stopService(Intent(context, KioskWatchdogService::class.java))
        KioskWatchdogService.notifyScannerVisible(false)
        KioskWatchdogService.setCheckIntervalForTesting(200)
        Intents.intending(hasComponent(ScannerActivity::class.java.name)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        )
    }

    @After
    fun tearDown() {
        KioskWatchdogService.setCheckIntervalForTesting(15_000)
        context.stopService(Intent(context, KioskWatchdogService::class.java))
        Intents.release()
    }

    @Test
    fun watchdogRelaunchesScannerWhenNotVisible() {
        KioskWatchdogService.requestImmediateCheck(context)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Intents.intended(hasComponent(ScannerActivity::class.java.name))
        KioskWatchdogService.notifyScannerVisible(true)
    }
}
