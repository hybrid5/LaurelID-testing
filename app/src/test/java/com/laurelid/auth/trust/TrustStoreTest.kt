package com.laurelid.auth.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.cert.CertificateFactory
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrustStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun loadsEmbeddedRoots() {
        val trustStore = ResourceTrustStore(context)
        val roots = trustStore.loadIacaRoots()
        assertTrue(roots.isNotEmpty())
    }

    @Test
    fun rejectsInvalidChain() {
        val trustStore = ResourceTrustStore(context)
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val bogus = certificateFactory.generateCertificate("""-----BEGIN CERTIFICATE-----\nMIIBbzCCARSgAwIBAgIUWlVsaXRlU2lnbmVyMAoGCCqGSM49BAMCMBUxEzARBgNV\nBAMMCnRlc3QtZGV2aWNlMB4XDTI1MTAwMjAwMDAwMFoXDTI2MTAwMjAwMDAwMFow\nFTETMBEGA1UEAwwKdGVzdC1kZXZpY2UwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNC\nAATFqye+HELD1XopZLzVa+0Y4Vu0nbbiqsJeIXtS1NT7y9plg9abRx8V9OBI7W7Z\nHv02mYt4PRt3vLwWoP4P2TZMo1MwUTAdBgNVHQ4EFgQULsCMaPw6Io67j5v73C/H\n8POhwUEwHwYDVR0jBBgwFoAULsCMaPw6Io67j5v73C/H8POhwUEwDwYDVR0TAQH/\nBAUwAwEB/zAKBggqhkjOPQQDAgNHADBEAiB4oPzxmJ6kG4AMyUpwRg4qOeIfAkHW\n/L/GSw3FISq7awIgLbVmSvNG0h+pzIKJ9hw3bWTCoX4UWKd8VzBOacppCVI=\n-----END CERTIFICATE-----""".trimIndent().byteInputStream())
        val result = trustStore.verifyChain(listOf(bogus as java.security.cert.X509Certificate), emptyList(), Instant.now())
        assertFalse(result)
    }
}

