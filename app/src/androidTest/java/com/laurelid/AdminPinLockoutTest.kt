package com.laurelid

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laurelid.config.AdminConfig
import com.laurelid.config.AdminPinManager
import com.laurelid.config.ConfigManager
import com.laurelid.config.EncryptedAdminPinStorage
import com.laurelid.ui.ScannerActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AdminPinLockoutTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var configManager: ConfigManager
    private lateinit var pinManager: AdminPinManager

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        configManager = ConfigManager(context)
        configManager.saveConfig(AdminConfig(demoMode = true))
        pinManager = AdminPinManager(EncryptedAdminPinStorage(context))
        pinManager.clearPin()
        pinManager.updatePin("123456")
    }

    @After
    fun tearDown() {
        pinManager.clearPin()
        configManager.saveConfig(AdminConfig())
    }

    @Test
    fun repeatedFailuresTriggerLockoutToast() {
        ActivityScenario.launch(ScannerActivity::class.java).use { scenario ->
            var decorView: android.view.View? = null
            scenario.onActivity { activity ->
                decorView = activity.window.decorView
                val method = activity.javaClass.getDeclaredMethod("handleAdminPinEntry", String::class.java)
                method.isAccessible = true
                repeat(AdminPinManager.MAX_ATTEMPTS) {
                    method.invoke(activity, "000000")
                }
            }

            val rootView = requireNotNull(decorView)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            onView(withText(startsWith("Too many attempts")))
                .inRoot(withDecorView(not(rootView)))
                .check(matches(isDisplayed()))
        }
    }
}
