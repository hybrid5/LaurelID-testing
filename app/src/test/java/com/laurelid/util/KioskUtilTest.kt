package com.laurelid.util

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class KioskUtilTest {

    private val baseContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun isLockTaskPermitted_returnsTrue_whenDevicePolicyAllowsPackage() {
        val stub = createDevicePolicyManagerStub(baseContext, lockTaskPermitted = true)
        val context = contextWithDevicePolicyManager(baseContext, stub)

        assertTrue(KioskUtil.isLockTaskPermitted(context))
    }

    @Test
    fun isLockTaskPermitted_returnsFalse_whenDevicePolicyDeniesPackage() {
        val stub = createDevicePolicyManagerStub(baseContext, lockTaskPermitted = false)
        val context = contextWithDevicePolicyManager(baseContext, stub)

        assertFalse(KioskUtil.isLockTaskPermitted(context))
    }

    @Test
    fun isLockTaskPermitted_returnsFalse_whenDevicePolicyUnavailable() {
        val context = object : ContextWrapper(baseContext) {
            override fun <T> getSystemService(serviceClass: Class<T>): T? {
                return null
            }

            override fun getSystemService(name: String): Any? {
                return null
            }
        }

        assertFalse(KioskUtil.isLockTaskPermitted(context))
    }

    private fun contextWithDevicePolicyManager(
        base: Context,
        manager: DevicePolicyManager,
    ): Context {
        return object : ContextWrapper(base) {
            override fun <T> getSystemService(serviceClass: Class<T>): T? {
                return if (serviceClass == DevicePolicyManager::class.java) {
                    @Suppress("UNCHECKED_CAST")
                    manager as T
                } else {
                    super.getSystemService(serviceClass)
                }
            }

            override fun getSystemService(name: String): Any? {
                return if (Context.DEVICE_POLICY_SERVICE == name) {
                    manager
                } else {
                    super.getSystemService(name)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun createDevicePolicyManagerStub(
        context: Context,
        lockTaskPermitted: Boolean,
    ): DevicePolicyManager {
        val interfaceClass = Class.forName("android.app.admin.IDevicePolicyManager")
        val handler = InvocationHandler { _: Any?, method: Method, _: Array<out Any?>? ->
            when (method.name) {
                "isLockTaskPermitted" -> lockTaskPermitted
                "isDeviceOwnerApp", "isProfileOwnerApp" -> lockTaskPermitted
                else -> defaultReturn(method.returnType)
            }
        }
        val proxy = Proxy.newProxyInstance(interfaceClass.classLoader, arrayOf(interfaceClass), handler)
        val constructor = DevicePolicyManager::class.java.getDeclaredConstructor(Context::class.java, interfaceClass)
        constructor.isAccessible = true
        return constructor.newInstance(context, proxy)
    }

    private fun defaultReturn(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }
}
