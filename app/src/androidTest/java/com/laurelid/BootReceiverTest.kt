package com.laurelid

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laurelid.kiosk.KioskWatchdogService
import com.laurelid.ui.ScannerActivity
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BootReceiverTest {

    @Test
    fun bootCompletedLaunchesScannerAndWatchdog() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val recordingContext = RecordingContext(baseContext)
        val receiver = BootReceiver()

        receiver.onReceive(recordingContext, Intent(Intent.ACTION_BOOT_COMPLETED))

        val startedService = recordingContext.startedServiceIntent
        val startedActivity = recordingContext.startedActivityIntent

        assertNotNull(startedService)
        assertEquals(KioskWatchdogService::class.java.name, startedService.component?.className)

        assertNotNull(startedActivity)
        assertEquals(ScannerActivity::class.java.name, startedActivity.component?.className)
        assertTrue(startedActivity.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(startedActivity.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedServiceIntent: Intent? = null
        var startedActivityIntent: Intent? = null

        override fun startForegroundService(service: Intent): ComponentName? {
            startedServiceIntent = service
            return service.component
        }

        override fun startService(service: Intent): ComponentName? {
            startedServiceIntent = service
            return service.component
        }

        override fun startActivity(intent: Intent) {
            startedActivityIntent = intent
        }
    }
}
