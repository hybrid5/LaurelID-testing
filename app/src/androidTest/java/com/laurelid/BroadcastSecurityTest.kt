package com.laurelid

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BroadcastSecurityTest {

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
