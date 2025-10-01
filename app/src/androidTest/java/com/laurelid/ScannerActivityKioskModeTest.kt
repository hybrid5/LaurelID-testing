package com.laurelid

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.laurelid.kiosk.KioskWatchdogService
import com.laurelid.ui.ScannerActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScannerActivityKioskModeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, KioskWatchdogService::class.java))
    }

    @Test
    fun immersiveStickyModeAndKeyguardAreApplied() {
        val scenario = ActivityScenario.launch(ScannerActivity::class.java)

        scenario.onActivity { activity ->
            val flags = activity.window.attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD != 0)
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED != 0)
            assertTrue(flags and WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON != 0)

            val decorFlags = activity.window.decorView.systemUiVisibility
            assertTrue(decorFlags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
            assertTrue(decorFlags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        }

        scenario.close()
    }
}
