package com.laurelid.verifier.trust

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrustStoreImplTest {

    @Test
    fun loadsBundledRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val trustStore = TrustStoreImpl(context)
        val roots = trustStore.loadIacaRoots()
        assertTrue(roots.isNotEmpty(), "Expected bundled roots to load")
    }
}
