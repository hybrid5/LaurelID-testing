package com.laurelid

import android.app.Application
import com.laurelid.kiosk.KioskWatchdogService
import com.laurelid.util.LogManager
import com.laurelid.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LaurelIdApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Logger.i("App", "LaurelID kiosk application initialized")
        KioskWatchdogService.start(this)
        appScope.launch {
            LogManager.purgeLegacyLogs(applicationContext)
            LogManager.purgeOldLogs(applicationContext)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
