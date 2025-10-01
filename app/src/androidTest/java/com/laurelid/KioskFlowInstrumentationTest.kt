package com.laurelid

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laurelid.config.AdminConfig
import com.laurelid.config.ConfigManager
import com.laurelid.ui.ResultActivity
import com.laurelid.ui.ScannerActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class KioskFlowInstrumentationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var configManager: ConfigManager

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        configManager = ConfigManager(context)
        configManager.saveConfig(AdminConfig(demoMode = true))
    }

    @After
    fun tearDown() {
        configManager.saveConfig(AdminConfig())
    }

    @Test
    fun demoModeHandleDemoPayloadLaunchesResultActivity() {
        Intents.init()
        try {
            Intents.intending(hasComponent(ResultActivity::class.java.name)).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent())
            )

            ActivityScenario.launch(ScannerActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val method = activity.javaClass.getDeclaredMethod("handleDemoPayload")
                    method.isAccessible = true
                    method.invoke(activity)
                }

                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Intents.intended(hasComponent(ResultActivity::class.java.name))
            }
        } finally {
            Intents.release()
        }
    }
}
