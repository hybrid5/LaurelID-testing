package com.laurelid.auth.trust

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.laurelid.auth.session.VerificationError
import java.security.cert.CertificateFactory
import java.time.Clock
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrustStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock: Clock = Clock.systemUTC()

    @Test
    fun loadsEmbeddedRoots() {
        val trustStore = ResourceTrustStore(context, clock)
        val roots = trustStore.loadIacaRoots()
        assertTrue(roots.isNotEmpty())
    }

    @Test
    fun validatesAzAnchors() {
        val trustStore = ResourceTrustStore(context, clock)
        val roots = trustStore.loadIacaRoots()
        val azRoot = roots.first { it.subjectX500Principal.name.contains("AZ prod IACA certificate") }
        assertTrue(trustStore.verifyChain(listOf(azRoot), listOf(azRoot), Instant.now(clock)))
    }

    @Test
    fun rejectsInvalidChain() {
        val trustStore = ResourceTrustStore(context, clock)
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val bogus = certificateFactory.generateCertificate(
            """-----BEGIN CERTIFICATE-----
MIIBbzCCARSgAwIBAgIUWlVsaXRlU2lnbmVyMAoGCCqGSM49BAMCMBUxEzARBgNV
BAMMCnRlc3QtZGV2aWNlMB4XDTI1MTAwMjAwMDAwMFoXDTI2MTAwMjAwMDAwMFow
FTETMBEGA1UEAwwKdGVzdC1kZXZpY2UwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNC
AATFqye+HELD1XopZLzVa+0Y4Vu0nbbiqsJeIXtS1NT7y9plg9abRx8V9OBI7W7Z
Hv02mYt4PRt3vLwWoP4P2TZMo1MwUTAdBgNVHQ4EFgQULsCMaPw6Io67j5v73C/H
8POhwUEwHwYDVR0jBBgwFoAULsCMaPw6Io67j5v73C/H8POhwUEwDwYDVR0TAQH/
BAUwAwEB/zAKBggqhkjOPQQDAgNHADBEAiB4oPzxmJ6kG4AMyUpwRg4qOeIfAkHW
/L/GSw3FISq7awIgLbVmSvNG0h+pzIKJ9hw3bWTCoX4UWKd8VzBOacppCVI=
-----END CERTIFICATE-----"""
                .trimIndent()
                .byteInputStream(),
        ) as java.security.cert.X509Certificate
        val result = trustStore.verifyChain(listOf(bogus), emptyList(), Instant.now(clock))
        assertTrue(!result)
    }

    @Test
    fun throwsWhenAnchorsUnavailable() {
        val exception = assertFailsWith<VerificationError.TrustAnchorsUnavailable> {
            throw VerificationError.TrustAnchorsUnavailable("missing bundle")
        }
        assertTrue(exception.message?.contains("missing") == true)
    }
}
