package com.laurelid.fallback.pdf417

import android.graphics.Bitmap
import javax.inject.Inject
import javax.inject.Singleton

/** Placeholder for PDF417 fallback scanning. */
@Singleton
class Pdf417Scanner @Inject constructor() {
    fun scan(bitmap: Bitmap): Result {
        // TODO: Integrate hardware PDF417 reader or ML Kit pipeline.
        return Result.Failed("PDF417 scanning not yet implemented")
    }

    sealed interface Result {
        data class Success(val rawData: String) : Result
        data class Failed(val reason: String) : Result
    }
}

