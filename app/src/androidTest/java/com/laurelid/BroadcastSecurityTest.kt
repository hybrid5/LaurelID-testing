package com.laurelid

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Before
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BroadcastSecurityTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun thirdPartyContextCannotInvokeBootReceiver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val attackerContext = instrumentation.context
        val component = ComponentName(
            instrumentation.targetContext,
            BootReceiver::class.java
        )

        val intent = Intent(Intent.ACTION_BOOT_COMPLETED).apply {
            component = component
        }

        assertThrows(SecurityException::class.java) {
            attackerContext.sendBroadcast(intent)
        }
    }
}
