package com.laurelid

import android.content.Context
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScannerLockTaskTest {

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
    fun entersLockTaskWhenPermitted() {
        ActivityScenario.launch(TestScannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetLockTaskFlag()
                activity.lockTaskPermitted = true
                activity.setConfigForTest(AdminConfig(demoMode = false))
                activity.invokeEnterLockTask()
            }

            scenario.onActivity { activity ->
                assertTrue(activity.startLockTaskCalled, "Lock task should be started when permitted")
            }
        }
    }

    @Test
    fun doesNotEnterLockTaskWhenNotPermitted() {
        ActivityScenario.launch(TestScannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.resetLockTaskFlag()
                activity.lockTaskPermitted = false
                activity.setConfigForTest(AdminConfig(demoMode = false))
                activity.invokeEnterLockTask()
            }

            scenario.onActivity { activity ->
                assertFalse(activity.startLockTaskCalled, "Lock task should not start without permission")
            }
        }
    }
}

