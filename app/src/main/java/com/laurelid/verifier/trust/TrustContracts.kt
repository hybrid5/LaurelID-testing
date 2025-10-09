package com.laurelid.verifier.trust

import java.security.cert.X509Certificate

/** Loads IACA root certificates that ship with the application. */
interface TrustStore {
    fun loadIacaRoots(): List<X509Certificate>
}
