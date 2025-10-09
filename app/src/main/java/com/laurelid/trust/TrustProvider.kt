package com.laurelid.trust

import java.security.cert.X509Certificate

/** Provides trust anchors from a specific source. */
fun interface TrustProvider {
    fun loadAnchors(): List<X509Certificate>
}
