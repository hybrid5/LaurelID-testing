package com.laurelid

import android.app.Activity
import android.app.Instrumentation
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laurelid.config.AdminConfig
import com.laurelid.ui.ResultActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScannerBleQrFlowTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hiltRule.inject()
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun handleBleOnlyQrPayloadNavigatesToResult() {
        Intents.intending(hasComponent(ResultActivity::class.java.name))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

        ActivityScenario.launch(TestScannerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setConfigForTest(AdminConfig(demoMode = false))
                activity.setProcessingForTest(false)

                val payload = buildBleOnlyQrPayload()
                activity.invokeHandleQrPayload(payload)
            }

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Intents.intended(hasComponent(ResultActivity::class.java.name))

            scenario.onActivity { activity ->
                assertFalse(activity.isProcessingForTest(), "Processing flag should reset after navigation")
            }
        }
    }

    private fun buildBleOnlyQrPayload(): String {
        val deviceResponse = buildDeviceResponse(subjectDid = "did:example:ble")
        val messages = JSONArray().apply {
            put(Base64.getEncoder().encodeToString(deviceResponse))
        }
        val handover = JSONObject().apply {
            put("ble", JSONObject().apply { put("messages", messages) })
        }
        return JSONObject().apply {
            put("version", 1)
            put("handover", handover)
        }.toString()
    }

    private fun buildDeviceResponse(subjectDid: String): ByteArray {
        val encoder = Base64.getEncoder()
        val nameSpace = JSONObject().apply {
            put("family_name", encoder.encodeToString("Doe".toByteArray()))
            put("given_name", encoder.encodeToString("Ble".toByteArray()))
        }
        val deviceSignedEntries = JSONObject().apply {
            put("org.iso.18013.5.1", nameSpace)
        }
        val json = JSONObject().apply {
            put("subjectDid", subjectDid)
            put("docType", "org.iso.18013.5.1.mDL")
            put("issuer", "AZ-MVD")
            put("ageOver21", true)
            put("issuerAuth", encoder.encodeToString("issuer-auth".toByteArray()))
            put("deviceSignedEntries", deviceSignedEntries)
            put("deviceSignedCose", encoder.encodeToString("device-signed".toByteArray()))
        }
        return json.toString().toByteArray()
    }
}

