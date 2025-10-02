package com.laurelid.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.laurelid.R
import com.laurelid.kiosk.KioskWatchdogService
import com.laurelid.util.KioskUtil

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        KioskUtil.applyKioskDecor(window)
        KioskUtil.blockBackPress(this)
    }

    override fun onResume() {
        super.onResume()
        KioskUtil.prepareForLockscreen(this)
        KioskUtil.setImmersiveMode(window)
        KioskWatchdogService.notifyScannerVisible(true)
    }

    override fun onPause() {
        KioskWatchdogService.notifyScannerVisible(false)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            KioskUtil.setImmersiveMode(window)
        }
    }
}
