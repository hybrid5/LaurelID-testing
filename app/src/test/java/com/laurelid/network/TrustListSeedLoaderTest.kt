package com.laurelid.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TrustListSeedLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manifestVerifier = TrustListTestAuthority.manifestVerifier()

    @Test
    fun `loads bundled trust seed from assets`() {
        val storage = AssetTrustListSeedStorage(context, "trust_seed.json")
        val loader = TrustListSeedLoader(storage, manifestVerifier)

        val seed = loader.load(nowMillis = 1_000L, currentBaseUrl = null)

        assertNotNull(seed)
        assertTrue(seed.payload.manifest.entries.isNotEmpty())
    }
}
