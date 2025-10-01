package com.laurelid

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.laurelid.ui.AdminActivity
import com.laurelid.ui.LoginActivity
import com.laurelid.ui.ResultActivity
import com.laurelid.ui.ScannerActivity
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class OrientationLockTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun kioskScreensLockedToPortrait() {
        val packageManager = context.packageManager
        val scannerInfo = packageManager.getActivityInfo(ComponentName(context, ScannerActivity::class.java), 0)
        val resultInfo = packageManager.getActivityInfo(ComponentName(context, ResultActivity::class.java), 0)
        val adminInfo = packageManager.getActivityInfo(ComponentName(context, AdminActivity::class.java), 0)
        val loginInfo = packageManager.getActivityInfo(ComponentName(context, LoginActivity::class.java), 0)

        listOf(scannerInfo, resultInfo, adminInfo, loginInfo).forEach { info ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
        }
    }
}
