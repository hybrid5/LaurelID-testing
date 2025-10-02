package com.laurelid.ui

import com.laurelid.config.AdminConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScannerActivityKioskTest {

    @Test
    fun shouldEnterLockTask_returnsFalse_whenDemoModeEnabled() {
        val config = AdminConfig(demoMode = true)

        assertFalse(ScannerActivity.shouldEnterLockTask(config, lockTaskPermitted = true))
    }

    @Test
    fun shouldEnterLockTask_returnsFalse_whenPermissionDenied() {
        val config = AdminConfig(demoMode = false)

        assertFalse(ScannerActivity.shouldEnterLockTask(config, lockTaskPermitted = false))
    }

    @Test
    fun shouldEnterLockTask_returnsTrue_whenAllowedAndNotDemo() {
        val config = AdminConfig(demoMode = false)

        assertTrue(ScannerActivity.shouldEnterLockTask(config, lockTaskPermitted = true))
    }
}
