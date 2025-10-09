package com.laurelid.auth.verifier

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds ISO/IEC 18013-5 presentation requests and applies minimisation policies so the kiosk only
 * processes attributes it truly needs.
 */
@Singleton
class PresentationRequestBuilder @Inject constructor() {

    fun requestedAttributes(): List<String> = listOf(
        AGE_OVER_21,
        GIVEN_NAME,
        FAMILY_NAME,
        "portrait",
    )

    fun minimize(claims: Map<String, Any?>): Map<String, Any?> {
        val requested = requestedAttributes().toSet()
        return claims.filterKeys { it in requested }
    }

    companion object {
        const val DEFAULT_DOC_TYPE = "org.iso.18013.5.1.mDL"
        const val AGE_OVER_21 = "age_over_21"
        const val GIVEN_NAME = "given_name"
        const val FAMILY_NAME = "family_name"
    }
}

