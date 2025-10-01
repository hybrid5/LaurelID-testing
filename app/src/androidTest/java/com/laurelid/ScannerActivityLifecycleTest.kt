package com.laurelid

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.laurelid.config.AdminConfig
import com.laurelid.config.ConfigManager
import com.laurelid.network.RetrofitModule
import com.laurelid.ui.ScannerActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScannerActivityLifecycleTest {

    private lateinit var context: Context
    private lateinit var configManager: ConfigManager

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        configManager = ConfigManager(context)
        configManager.saveConfig(AdminConfig(demoMode = true))
    }

    @After
    fun tearDown() {
        configManager.saveConfig(AdminConfig())
    }

    @Test
    fun invalidEndpointOverrideFallsBackToDefaultUrl() {
        configManager.saveConfig(
            AdminConfig(apiEndpointOverride = "not-a-valid-url", demoMode = true)
        )

        ActivityScenario.launch(ScannerActivity::class.java).use { scenario ->
            val baseUrl = scenario.withActivity { it.currentBaseUrlForTest() }
            assertEquals(RetrofitModule.DEFAULT_BASE_URL, baseUrl)
        }
    }

    @Test
    fun resumeRebuildsDependenciesWhenEndpointChanges() {
        configManager.saveConfig(AdminConfig(demoMode = true))

        ActivityScenario.launch(ScannerActivity::class.java).use { scenario ->
            val initialBaseUrl = scenario.withActivity { it.currentBaseUrlForTest() }
            assertEquals(RetrofitModule.DEFAULT_BASE_URL, initialBaseUrl)

            val overrideUrl = "https://override.example.com/"
            configManager.saveConfig(AdminConfig(apiEndpointOverride = overrideUrl, demoMode = true))

            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            val rebuiltBaseUrl = scenario.withActivity { it.currentBaseUrlForTest() }
            assertEquals(overrideUrl, rebuiltBaseUrl)
        }
    }
}

private fun <A : AppCompatActivity, R> ActivityScenario<A>.withActivity(block: (A) -> R): R {
    var result: R? = null
    onActivity { activity ->
        result = block(activity)
    }
    return requireNotNull(result)
}
