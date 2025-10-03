package com.laurelid.integrity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayIntegrityGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        PlayIntegrityGate.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        PlayIntegrityGate.resetForTesting()
    }

    @Test
    fun allowsAccessWhenDeviceIntegrityIsMet() = runTest {
        PlayIntegrityGate.setHelperFactoryForTesting { FakeProvider(PlayIntegrityVerdict.MEETS_DEVICE_INTEGRITY) }

        assertTrue(PlayIntegrityGate.isAdminAccessAllowed(context))
    }

    @Test
    fun blocksAccessWhenDeviceIntegrityFails() = runTest {
        PlayIntegrityGate.setHelperFactoryForTesting { FakeProvider(PlayIntegrityVerdict.FAILED_DEVICE_INTEGRITY) }

        assertFalse(PlayIntegrityGate.isAdminAccessAllowed(context))
    }

    private class FakeProvider(
        private val verdict: PlayIntegrityVerdict,
    ) : PlayIntegrityVerdictProvider {
        override suspend fun fetchVerdict(): PlayIntegrityVerdict = verdict
    }
}
