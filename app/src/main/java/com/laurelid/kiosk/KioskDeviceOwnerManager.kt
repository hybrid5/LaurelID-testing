package com.laurelid.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.laurelid.BuildConfig
import com.laurelid.util.Logger

/**
 * Centralizes the device-owner policies needed for kiosk mode.
 */
object KioskDeviceOwnerManager {

    private const val TAG = "KioskDeviceOwner"

    fun enforcePolicies(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null) {
            Logger.w(TAG, "DevicePolicyManager unavailable; kiosk policies cannot be applied")
            if (!BuildConfig.DEBUG) {
                throw IllegalStateException("Release builds require device owner access")
            }
            return
        }
        val component = ComponentName(context, LaurelIdDeviceAdminReceiver::class.java)
        val packageName = context.packageName
        val isDeviceOwner = try {
            dpm.isDeviceOwnerApp(packageName)
        } catch (e: SecurityException) {
            Logger.e(TAG, "Unable to determine device owner state", e)
            false
        }

        if (isDeviceOwner) {
            ensureLockTaskPolicies(dpm, component, packageName)
        } else {
            Logger.w(TAG, "App is not device owner; kiosk protections are degraded")
        }

        if (!isDeviceOwner && !BuildConfig.DEBUG) {
            throw IllegalStateException("Release builds require the app to be device owner")
        }
    }

    private fun ensureLockTaskPolicies(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
    ) {
        try {
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
        } catch (e: SecurityException) {
            Logger.e(TAG, "Failed to set lock task packages", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var features = DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                features = features or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            }
            try {
                dpm.setLockTaskFeatures(admin, features)
            } catch (e: SecurityException) {
                Logger.e(TAG, "Failed to set lock task features", e)
            }
        }
    }
}
