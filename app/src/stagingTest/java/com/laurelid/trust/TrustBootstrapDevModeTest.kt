package com.laurelid.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laurelid.BuildConfig
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrustBootstrapDevModeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun loadsDevRootsInStaging() {
        assertTrue(BuildConfig.DEV_MODE)
        val bootstrap = TrustBootstrap(
            AssetTrustProvider(context, AssetTrustProvider.DEFAULT_ASSET_PATH),
            Clock.systemUTC(),
            AssetTrustProvider(context, "trust/test_roots"),
        )
        val anchors = bootstrap.refreshAnchors()
        assertTrue(anchors.isNotEmpty())
        assertTrue(anchors.any { it.subjectX500Principal.name.contains("Dev Test Root CA") })
        assertTrue(!bootstrap.status.value.degraded)
    }
}
