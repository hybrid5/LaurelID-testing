package com.laurelid

import android.app.Application
import com.laurelid.kiosk.KioskDeviceOwnerManager
import com.laurelid.observability.IEventExporter
import com.laurelid.observability.StructuredEventLogger
import com.laurelid.util.LogManager
import com.laurelid.util.Logger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@HiltAndroidApp
class LaurelIdApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject lateinit var logManager: LogManager
    @Inject lateinit var eventExporter: IEventExporter

    override fun onCreate() {
        super.onCreate()

        Logger.i("App", "LaurelID kiosk application initialized")

        // Register telemetry/logging safely
        StructuredEventLogger.registerExporter(eventExporter)
        KioskDeviceOwnerManager.enforcePolicies(this)

        // ⚠️ Do NOT start foreground services here on Android 14+
        // The first visible activity (e.g., ScannerActivity.onStart) should call:
        //   ContextCompat.startForegroundService(context, Intent(context, KioskWatchdogService::class.java))
        // Instead of starting it here, just log readiness.
        Logger.i("App", "Watchdog service startup deferred until activity is visible")

        // Background log maintenance
        appScope.launch {
            logManager.purgeLegacyLogs()
            logManager.purgeOldLogs()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
