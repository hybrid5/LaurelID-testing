package com.laurelid.util

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object KioskUtil {

    private const val TAG = "KioskUtil"

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

    fun isLockTaskPermitted(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val packageName = context.packageName
        return try {
            dpm.isLockTaskPermitted(packageName)
        } catch (e: SecurityException) {
            Logger.e(TAG, "SecurityException checking lock task permission", e)
            false
        } catch (e: Exception) {
            Logger.e(TAG, "Exception checking lock task permission", e)
            false
        }
    }

    fun startLockTaskIfPermitted(
        activity: ComponentActivity,
        lockTaskPermitted: Boolean? = null,
    ): Boolean {
        val permitted = lockTaskPermitted ?: isLockTaskPermitted(activity)
        if (!permitted) {
            Logger.i(TAG, "Lock task not permitted for package ${activity.packageName}")
            return false
        }
        return try {
            activity.startLockTask()
            true
        } catch (e: SecurityException) {
            Logger.w(TAG, "Lock task not permitted by system", e)
            false
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Unable to enter lock task mode", e)
            false
        }
    }
}
