package com.laurelid.verifier.trust

import android.content.Context
import com.laurelid.R
import java.io.BufferedInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustStoreImpl @Inject constructor(
    private val context: Context,
) : TrustStore {

    private val cache = lazy { loadCertificates() }

    override fun loadIacaRoots(): List<X509Certificate> = cache.value

    private fun loadCertificates(): List<X509Certificate> {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        context.resources.openRawResource(R.raw.iaca_roots).use { stream ->
            val buffered = BufferedInputStream(stream)
            return certificateFactory.generateCertificates(buffered).map { it as X509Certificate }
        }
    }
}
