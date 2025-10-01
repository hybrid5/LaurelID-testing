package com.laurelid

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.IntentFilter
import android.nfc.NfcAdapter
import com.laurelid.config.AdminConfig
import com.laurelid.ui.ScannerActivity
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@AndroidEntryPoint
class TestScannerActivity : ScannerActivity() {

    var lockTaskPermitted: Boolean = true
    var startLockTaskCalled: Boolean = false
        private set

    private lateinit var devicePolicyManagerStub: DevicePolicyManager

    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) {
            super.attachBaseContext(null)
            return
        }

        devicePolicyManagerStub = createDevicePolicyManagerStub(newBase)
        val wrapper = object : ContextWrapper(newBase) {
            override fun getSystemService(name: String): Any? {
                return if (Context.DEVICE_POLICY_SERVICE == name) {
                    devicePolicyManagerStub
                } else {
                    super.getSystemService(name)
                }
            }
        }
        super.attachBaseContext(wrapper)
    }

    override fun startLockTask() {
        startLockTaskCalled = true
    }

    fun resetLockTaskFlag() {
        startLockTaskCalled = false
    }

    fun setConfigForTest(config: AdminConfig) {
        setPrivateField("currentConfig", config)
    }

    fun invokeEnterLockTask() {
        invokePrivateMethod("enterLockTaskIfPermitted")
    }

    fun invokeEnableForegroundDispatch() {
        invokePrivateMethod("enableForegroundDispatch")
    }

    fun invokeDisableForegroundDispatch() {
        invokePrivateMethod("disableForegroundDispatch")
    }

    fun installRecordingNfcAdapter(): RecordingNfcAdapter {
        val recording = RecordingNfcAdapter(this)
        setPrivateField("nfcAdapter", recording.adapter)
        return recording
    }

    fun pendingIntentForTest(): PendingIntent? = getPrivateField("nfcPendingIntent")

    fun intentFiltersForTest(): Array<IntentFilter>? = getPrivateField("nfcIntentFilters")

    fun setProcessingForTest(value: Boolean) {
        setPrivateField("isProcessingCredential", value)
    }

    fun isProcessingForTest(): Boolean = getPrivateField("isProcessingCredential") ?: false

    fun invokeHandleQrPayload(payload: String) {
        invokePrivateMethod("handleQrPayload", payload)
    }

    private fun setPrivateField(name: String, value: Any?) {
        val field = ScannerActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(name: String): T? {
        val field = ScannerActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as? T
    }

    private fun invokePrivateMethod(name: String, vararg args: Any?) {
        val parameterTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        val method = if (parameterTypes.isEmpty()) {
            ScannerActivity::class.java.getDeclaredMethod(name)
        } else {
            ScannerActivity::class.java.getDeclaredMethod(name, *parameterTypes)
        }
        method.isAccessible = true
        method.invoke(this, *args)
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun createDevicePolicyManagerStub(context: Context): DevicePolicyManager {
        val interfaceClass = Class.forName("android.app.admin.IDevicePolicyManager")
        val handler = InvocationHandler { _: Any?, method: Method, _: Array<out Any?>? ->
            when (method.name) {
                "isDeviceOwnerApp", "isProfileOwnerApp", "isLockTaskPermitted" -> lockTaskPermitted
                else -> defaultReturn(method.returnType)
            }
        }
        val proxy = Proxy.newProxyInstance(interfaceClass.classLoader, arrayOf(interfaceClass), handler)
        val constructor = DevicePolicyManager::class.java.getDeclaredConstructor(Context::class.java, interfaceClass)
        constructor.isAccessible = true
        return constructor.newInstance(context, proxy)
    }

    class RecordingNfcAdapter(context: Context) {
        val adapter: NfcAdapter
        var lastEnableComponent: ComponentName? = null
            private set
        var lastDisableComponent: ComponentName? = null
            private set
        var lastPendingIntent: PendingIntent? = null
            private set
        var lastIntentFilters: Array<IntentFilter>? = null
            private set

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        init {
            val interfaceClass = Class.forName("android.nfc.INfcAdapter")
            val handler = InvocationHandler { _: Any?, method: Method, arguments: Array<out Any?>? ->
                when (method.name) {
                    "isEnabled" -> true
                    "getState" -> NfcAdapter.STATE_ON
                    "enableForegroundDispatch" -> {
                        lastEnableComponent = arguments?.getOrNull(0) as? ComponentName
                        lastPendingIntent = arguments?.getOrNull(1) as? PendingIntent
                        @Suppress("UNCHECKED_CAST")
                        lastIntentFilters = arguments?.getOrNull(2) as? Array<IntentFilter>
                        null
                    }
                    "disableForegroundDispatch" -> {
                        lastDisableComponent = arguments?.getOrNull(0) as? ComponentName
                        null
                    }
                    else -> defaultReturn(method.returnType)
                }
            }
            val proxy = Proxy.newProxyInstance(interfaceClass.classLoader, arrayOf(interfaceClass), handler)
            val constructor = NfcAdapter::class.java.getDeclaredConstructor(Context::class.java, interfaceClass)
            constructor.isAccessible = true
            adapter = constructor.newInstance(context, proxy)
        }
    }

    companion object {
        private fun defaultReturn(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            List::class.java -> emptyList<Any>()
            else -> null
        }
    }
}

