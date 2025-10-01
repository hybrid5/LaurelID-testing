package com.laurelid.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VerificationResult(
    val success: Boolean,
    val ageOver21: Boolean?, // Made nullable to align with previous uses where it might be unknown
    val issuer: String?,      // Made nullable
    val subjectDid: String?,  // Made nullable
    val docType: String?,     // Made nullable
    val error: String?,
    val trustStale: Boolean?
) : Parcelable
