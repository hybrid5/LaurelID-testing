package com.laurelid.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laurelid.BuildConfig
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrustBootstrapProdModeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun productionDoesNotLoadDevRoots() {
        assertFalse(BuildConfig.DEV_MODE)
        val bootstrap = TrustBootstrap(
            AssetTrustProvider(context, AssetTrustProvider.DEFAULT_ASSET_PATH),
            Clock.systemUTC(),
        )
        val anchors = bootstrap.refreshAnchors()
        assertTrue(anchors.isNotEmpty())
        assertFalse(anchors.any { it.subjectX500Principal.name.contains("Dev Test Root CA") })
    }
}
