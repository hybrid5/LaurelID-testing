package com.laurelid.util

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager // Added
import android.content.Context // Added
import android.os.Build // Added for API level checks potentially
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object KioskUtil {

    fun applyKioskDecor(window: Window) {
        keepScreenOn(window)
        setImmersiveMode(window)
    }

    fun prepareForLockscreen(activity: ComponentActivity) {
        val window = activity.window
        window.addFlags(
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }

        val keyguardManager = activity.getSystemService(KeyguardManager::class.java)
        keyguardManager?.requestDismissKeyguard(activity, null)
    }

    fun keepScreenOn(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun setImmersiveMode(window: Window) {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun blockBackPress(activity: ComponentActivity) {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Block back press in kiosk mode.
            }
        })
    }

    fun showSystemUI(window: Window) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    // Added function
    fun isLockTaskPermitted(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        // A simple check could be if the app is a device owner or profile owner of an affiliated user.
        // For full lock task mode without user interaction, being a device owner is typical.
        // On Android P and above, specific lock task features require the app to be whitelisted
        // by the DPC (Device Policy Controller) if it's not the DPC itself.
        return try {
            dpm.isDeviceOwnerApp(context.packageName) || dpm.isProfileOwnerApp(context.packageName)
            // More robust check for specific lock task features:
            // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            //    dpm.isLockTaskPermitted(context.packageName)
            // } else {
            //    // On older versions, being device owner was often enough
            //    dpm.isDeviceOwnerApp(context.packageName)
            // }
        } catch (e: SecurityException) {
            // This might happen if querying device/profile owner status without proper setup.
            Logger.e("KioskUtil", "SecurityException checking lock task permission", e)
            false
        } catch (e: Exception) {
            Logger.e("KioskUtil", "Exception checking lock task permission", e)
            false
        }
    }
}
