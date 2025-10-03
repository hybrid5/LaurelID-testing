package com.laurelid.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.laurelid.util.Logger

/**
 * Device admin receiver required so the kiosk app can become a device owner.
 * Only logs lifecycle callbacks for observability – all policy work happens
 * in [KioskDeviceOwnerManager].
 */
class LaurelIdDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Logger.i(TAG, "Device admin enabled for ${componentName(context)}")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Logger.w(TAG, "Device admin disabled for ${componentName(context)}")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Logger.i(TAG, "Lock task mode entered for package $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Logger.i(TAG, "Lock task mode exited")
    }

    private fun componentName(context: Context): ComponentName {
        return ComponentName(context, javaClass)
    }

    companion object {
        private const val TAG = "DeviceAdminReceiver"
    }
}
